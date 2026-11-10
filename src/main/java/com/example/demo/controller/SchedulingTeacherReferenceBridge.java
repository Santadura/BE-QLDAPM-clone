package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.controller.SchedulingAssignmentReferenceBridge.Event;
import com.example.demo.controller.SchedulingAssignmentReferenceBridge.Receipt;

/**
 * Private, default-OFF Core projection for Scheduling-owned TEACHING CREATE.
 * Validates a live TC+teacher, exact versioned Academic lease and durable
 * command claim. Teacher reference, immutable receipt and claim commit in
 * one Core transaction; no public schedule write route is changed.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reference/teaching")
@ConditionalOnProperty(name="iig.scheduling.teacher-reference-writes-enabled",
    havingValue="true")
public class SchedulingTeacherReferenceBridge {
    private record Prior(long revision,String eventId,String hash) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final byte[] key;

    public SchedulingTeacherReferenceBridge(JdbcTemplate db,ObjectMapper json,
            @Value("${iig.scheduling.internal-key}") String secret) {
        if(secret==null || secret.isBlank()) throw new IllegalArgumentException(
            "SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception ex) { throw new IllegalStateException("SHA-256 missing",ex); }
    }
    private static String str(JsonNode n,String key) {
        return n.hasNonNull(key)?n.get(key).asText():null;
    }
    private static boolean safe(String value,int max) {
        return value!=null && value.length()<=max &&
            value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
    private static void deny(String reason) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }

    @PostMapping
    @Transactional
    public Receipt apply(@RequestHeader(value="X-Internal-Key",required=false) String secret,
                         @RequestBody Event event) {
        if(!MessageDigest.isEqual(key,
            (secret==null?"":secret).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid teacher reference service key");
        if(event==null || !safe(event.eventId(),50) ||
                !safe(event.actorUsername(),100) || !safe(event.requestKey(),128) ||
                !safe(event.scheduleId(),50) ||
                !"TEACHING".equals(event.sourceType()) ||
                !"ASSIGN_TEACHER".equals(event.operation()) ||
                !"UPSERT".equals(event.eventKind()) || event.revision()!=1 ||
                event.payloadHash()==null ||
                !event.payloadHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only a TEACHING revision-1 creation may be mirrored");

        JsonNode p;
        try {
            p=json.readTree(event.payloadJson());
            if(p==null || !p.isObject()) throw new IllegalArgumentException("JSON object required");
            String canonical=json.writeValueAsString(json.convertValue(p,Object.class));
            if(!MessageDigest.isEqual(
                digest(canonical).getBytes(StandardCharsets.US_ASCII),
                event.payloadHash().getBytes(StandardCharsets.US_ASCII)))
                deny("Teacher event digest mismatch");
        } catch(ResponseStatusException ex) { throw ex;
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid teacher event JSON",ex);
        }
        var allowed=java.util.Set.of("id","teacherId","classId","sessionId",
            "date","startTime","endTime","status","assignedBy",
            "academicLeaseId","classVersion","sessionVersion",
            "teacherName","className","classCode");
        for(var it=p.fieldNames();it.hasNext();) {
            if(!allowed.contains(it.next())) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,"Unknown teacher reference field");
        }
        String teacher=str(p,"teacherId"),cls=str(p,"classId"),
            session=str(p,"sessionId"),academicLease=str(p,"academicLeaseId"),
            manager=str(p,"assignedBy");
        if(!event.scheduleId().equals(str(p,"id")) ||
                !safe(teacher,50) || !safe(cls,50) || !safe(session,50) ||
                !safe(academicLease,50) || !safe(manager,50) ||
                !"ASSIGNED".equals(str(p,"status")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact TEACHING, class, manager and lease identities are required");
        LocalDate date;LocalTime start,end;
        long cv=p.path("classVersion").asLong(0),sv=p.path("sessionVersion").asLong(0);
        try {
            date=LocalDate.parse(str(p,"date"));
            start=LocalTime.parse(str(p,"startTime"));
            end=LocalTime.parse(str(p,"endTime"));
            if(!end.isAfter(start) || cv<1 || sv<1)
                throw new IllegalArgumentException("Invalid versioned lesson");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact planned-session date, time and versions required",ex);
        }

        List<Long> mode=db.query(
            "SELECT writer_generation FROM scheduling_assignment_writer_control " +
            "WHERE singleton=TRUE AND writer_mode='QUIESCED' FOR SHARE",
            (rs,i)->rs.getLong(1));
        if(mode.size()!=1 || mode.get(0)<1) deny("Core TEACHING writer is not quiesced");
        // Check exact committed receipts BEFORE inspecting current account
        // state or lease expiry. This recovers a successfully committed Core
        // write after a lost HTTP response without authorizing a new one.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",teacher);
        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",
            "assignment-class:"+cls);
        List<Prior> receipts=db.query(
            "SELECT last_revision,last_event_id,payload_hash FROM " +
            "scheduling_assignment_reference_receipts WHERE source_type='TEACHING' " +
            "AND schedule_id=? FOR UPDATE",
            (rs,i)->new Prior(rs.getLong(1),rs.getString(2),rs.getString(3)),
            event.scheduleId());
        if(!receipts.isEmpty()) {
            Prior prior=receipts.get(0);
            Long exact=db.queryForObject(
                "SELECT count(*) FROM scheduling_assignment_reference_event_receipts " +
                "WHERE source_type='TEACHING' AND schedule_id=? AND revision=1 " +
                "AND event_id=? AND payload_hash=? AND actor_username=? " +
                "AND operation='ASSIGN_TEACHER' AND request_key=?",
                Long.class,event.scheduleId(),event.eventId(),event.payloadHash(),
                event.actorUsername(),event.requestKey());
            if(prior.revision()==1 && prior.eventId().equals(event.eventId()) &&
                    prior.hash().equals(event.payloadHash()) &&
                    exact!=null && exact==1) {
                // An HTTP timeout after Core COMMIT must be replayable even
                // if the lease expires or TC/teacher are later deactivated.
                // Only exact already-committed evidence is accepted; verify
                // the Core reference row and permanently confirmed claim.
                Long actual=db.queryForObject(
                    "SELECT count(*) FROM teaching_schedules WHERE teaching_schedule_id=? " +
                    "AND teacher_id=? AND class_id=? AND date=? " +
                    "AND start_time=? AND end_time=? AND status='ASSIGNED' " +
                    "AND assigned_by=?",
                    Long.class,event.scheduleId(),teacher,cls,date,start,end,manager);
                Long consumed=db.queryForObject(
                    "SELECT count(*) FROM scheduling_assignment_writer_claims " +
                    "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
                    "AND request_key=? AND manager_employee_id=? AND status='CONFIRMED'",
                    Long.class,event.actorUsername(),event.requestKey(),manager);
                if(actual==null || actual!=1 || consumed==null || consumed!=1)
                    deny("Committed teacher reference, immutable receipt and claim diverged");
                return new Receipt(event.eventId(),1,"ALREADY_APPLIED");
            }
            deny("Teaching event revision or immutable receipt differs");
        }
        Long tc=db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.username=? AND e.status='ACTIVE' " +
            "AND u.status='ACTIVE' AND u.role_id='TC'",
            Long.class,manager,event.actorUsername());
        if(tc==null || tc!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current TC management role required");
        Long worker=db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.status='ACTIVE' " +
            "AND u.status='ACTIVE' AND u.role_id='TEACHER'",Long.class,teacher);
        if(worker==null || worker!=1) deny("Teacher is no longer active");

        List<String> claims=db.query(
            "SELECT status FROM scheduling_assignment_writer_claims " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? AND manager_employee_id=? FOR SHARE",
            (rs,i)->rs.getString(1),event.actorUsername(),event.requestKey(),manager);
        if(claims.size()!=1) deny("Durable TC writer admission is missing");

        if(!"PENDING".equals(claims.get(0)))
            deny("Teacher command claim has already been consumed");
        String scoped=digest("iig-academic-lease-v1\n"+event.actorUsername()+
            "\nASSIGN_TEACHER\n"+event.requestKey());
        List<String> lease=db.query(
            "SELECT l.lease_id FROM scheduling_academic_session_leases l " +
            "JOIN classes c ON c.class_id=l.class_id " +
            "JOIN class_sessions s ON s.class_session_id=l.class_session_id " +
            "AND s.class_id=l.class_id " +
            "WHERE l.lease_id=? AND l.class_id=? AND l.class_session_id=? " +
            "AND l.command_key=? AND l.expires_at>clock_timestamp() " +
            "AND l.expected_writer_generation=? AND l.expected_class_version=? " +
            "AND l.expected_session_version=? " +
            "AND c.scheduling_version=l.expected_class_version " +
            "AND s.scheduling_version=l.expected_session_version " +
            "AND c.status IN ('READY','RUNNING') AND s.status='PLANNED' " +
            "AND s.date>=c.start_date AND s.date<=c.end_date " +
            "AND s.date=? AND s.start_time=? AND s.end_time=? " +
            "FOR SHARE OF l,c,s",
            (rs,i)->rs.getString(1),academicLease,cls,session,scoped,
            mode.get(0),cv,sv,date,start,end);
        if(lease.size()!=1) deny("Academic lease expired or exact session changed");
        Long existing=db.queryForObject(
            "SELECT count(*) FROM teaching_schedules WHERE teaching_schedule_id=?",
            Long.class,event.scheduleId());
        Long teacherConflict=db.queryForObject(
            "SELECT count(*) FROM teaching_schedules " +
            "WHERE teacher_id=? AND date=? AND status='ASSIGNED' " +
            "AND start_time<? AND end_time>?",Long.class,teacher,date,end,start);
        Long classConflict=db.queryForObject(
            "SELECT count(*) FROM teaching_schedules " +
            "WHERE class_id=? AND date=? AND status='ASSIGNED' " +
            "AND start_time<? AND end_time>?",Long.class,cls,date,end,start);
        if(existing==null || teacherConflict==null || classConflict==null ||
                existing!=0 || teacherConflict>0 || classConflict>0)
            deny("Teacher ID or committed timetable conflicts with this assignment");

        db.queryForObject(
            "SELECT set_config('iig.assignment_reference_projection','true',true)",
            String.class);
        if(db.update(
            "INSERT INTO teaching_schedules (teaching_schedule_id,teacher_id,class_id," +
            "date,start_time,end_time,status,assigned_by) VALUES(?,?,?,?,?,?,'ASSIGNED',?)",
            event.scheduleId(),teacher,cls,date,start,end,manager)!=1)
            throw new IllegalStateException("Teacher reference insert did not commit");
        db.update(
            "INSERT INTO scheduling_assignment_reference_receipts " +
            "(source_type,schedule_id,last_revision,last_event_id,payload_hash,actor_username) " +
            "VALUES('TEACHING',?,1,?,?,?)",
            event.scheduleId(),event.eventId(),event.payloadHash(),event.actorUsername());
        db.update(
            "INSERT INTO scheduling_assignment_reference_event_receipts " +
            "(event_id,source_type,schedule_id,revision,payload_hash," +
            "actor_username,operation,request_key) " +
            "VALUES(?,'TEACHING',?,1,? ,?,'ASSIGN_TEACHER',?)",
            event.eventId(),event.scheduleId(),event.payloadHash(),
            event.actorUsername(),event.requestKey());
        db.update(
            "INSERT INTO scheduling_assignment_outbox " +
            "(source_type,schedule_id,version,delivered_at) " +
            "VALUES('TEACHING',?,1,NOW()) ON CONFLICT(source_type,schedule_id) " +
            "DO UPDATE SET version=EXCLUDED.version,delivered_at=NOW(),updated_at=NOW()",
            event.scheduleId());
        if(db.update(
            "UPDATE scheduling_assignment_writer_claims SET status='CONFIRMED'," +
            "confirmed_at=NOW() WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? AND manager_employee_id=? AND status='PENDING'",
            event.actorUsername(),event.requestKey(),manager)!=1)
            throw new IllegalStateException("Teacher reference writer claim was not confirmed");
        return new Receipt(event.eventId(),1,"APPLIED");
    }
}

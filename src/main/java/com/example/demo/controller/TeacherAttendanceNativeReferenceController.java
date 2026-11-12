package com.example.demo.controller;

import com.example.demo.service.SchedulingAccess;
import com.example.demo.service.TeacherAttendanceLiveFactsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE idempotent projection of a committed native Attendance command.
 *
 * Core is NOT authoritative in ATTENDANCE mode. This adapter can only apply
 * an ORIGINAL, immutable Attendance event already admitted by an exact open
 * Core claim. Source row, audit, source outbox and reference receipt all
 * commit or roll back in ONE Core PostgreSQL transaction.
 *
 * Disabled by default and never exposed by Gateway.
 */
@RestController
@RequestMapping("/internal/attendance/native/reference")
@ConditionalOnProperty(name="iig.attendance.native-reference-enabled",
    havingValue="true")
public class TeacherAttendanceNativeReferenceController {
    public record Request(String payloadCanonical,String payloadHash) {}
    public record Receipt(String eventId,String teachingScheduleId,
        long version,String payloadHash,String outcome,JsonNode response) {}
    private record Claim(String claimId,String actor,String employeeId,
        String classId,String scheduleId,String requestKey,String requestHash,
        String status,String oldId,String oldStatus,String factsToken,
        long generation,String state,String eventId,String confirmedHash,
        Long confirmedVersion) {}
    private record Existing(String id,String status,Instant recordedAt) {}
    private record Modes(String attendance,long generation,
        String scheduling,String academic) {}

    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TeacherAttendanceLiveFactsService facts;
    private final SchedulingAccess access;
    private final byte[] secret;

    public TeacherAttendanceNativeReferenceController(JdbcTemplate db,
        ObjectMapper mapper,TeacherAttendanceLiveFactsService facts,
        SchedulingAccess access,
        @Value("${iig.attendance.internal-key}") String serviceKey) {
        if(serviceKey==null || serviceKey.isBlank())
            throw new IllegalArgumentException("Native Attendance private key required");
        this.db=db;this.mapper=mapper;this.facts=facts;this.access=access;
        secret=serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    private static String value(JsonNode json,String key) {
        if(!json.path(key).isTextual() || json.path(key).asText().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance signed event lacks "+key);
        return json.path(key).asText();
    }

    private static String nullable(JsonNode json,String key) {
        JsonNode found=json.path(key);
        return found.isNull()?null:value(json,key);
    }

    @PostMapping
    @Transactional
    public Receipt commit(
        @RequestHeader(value="X-Internal-Key",required=false) String key,
        @RequestBody Request request) {
        if(!MessageDigest.isEqual(secret,
            (key==null?"":key).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private native Attendance reference key");
        if(request==null || request.payloadCanonical()==null ||
            request.payloadCanonical().length()>8192 ||
            request.payloadHash()==null ||
            !request.payloadHash().matches("[0-9a-f]{64}") ||
            !request.payloadHash().equals(sha(request.payloadCanonical())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance canonical bytes or digest invalid");

        final JsonNode body;
        try {body=mapper.readTree(request.payloadCanonical());}
        catch(Exception ex) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Native Attendance event is malformed",ex);}
        if(body==null || !body.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance event must be a signed object");
        String event=value(body,"eventId");
        String claimId=value(body,"coreClaimId");
        String schedule=value(body,"teachingScheduleId");
        String actor=value(body,"actorUsername");
        String status=value(body,"status");
        String attendanceId=value(body,"attendanceId");
        String employeeId=value(body,"recordedBy");
        String classId=value(body,"classId");
        String requestKey=value(body,"requestKey");
        String requestHash=value(body,"requestHash");
        String token=value(body,"factsToken");
        String previousId=nullable(body,"previousAttendanceId");
        String previousStatus=nullable(body,"previousStatus");
        long generation=body.path("writerGeneration").asLong(-1);
        long version=body.path("version").asLong(-1);
        final Instant recordedAt,updatedAt;
        try {
            recordedAt=Instant.parse(value(body,"recordedAt"));
            updatedAt=Instant.parse(value(body,"updatedAt"));
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance event timestamps invalid",ex);
        }
        if(!event.matches("[0-9a-fA-F-]{36}") ||
            !claimId.matches("[0-9a-fA-F-]{36}") ||
            !schedule.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !employeeId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !attendanceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !Set.of("PRESENT","ABSENT","LATE").contains(status) ||
            !requestHash.matches("[0-9a-f]{64}") ||
            !token.matches("[0-9a-f]{64}") ||
            version<1 || generation<2 || recordedAt.isAfter(updatedAt))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance event identity or version invalid");

        List<Claim> claims=db.query(
            "SELECT claim_id::text,actor_username,actor_employee_id,class_id,"+
            "teaching_schedule_id,request_key,request_hash,attendance_status,"+
            "previous_attendance_id,previous_status,facts_token,writer_generation,"+
            "state,confirmed_event::text,confirmed_hash,confirmed_version "+
            "FROM teacher_attendance_native_claims WHERE claim_id=CAST(? AS UUID) "+
            "FOR UPDATE",
            (rs,i)->new Claim(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getString(10),rs.getString(11),rs.getLong(12),
                rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getObject(16)==null?null:rs.getLong(16)),claimId);
        if(claims.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No Core-admitted native Attendance claim");
        Claim c=claims.get(0);
        if(!actor.equals(c.actor()) || !employeeId.equals(c.employeeId()) ||
            !classId.equals(c.classId()) || !schedule.equals(c.scheduleId()) ||
            !requestKey.equals(c.requestKey()) || !status.equals(c.status()) ||
            !requestHash.equals(c.requestHash()) || !token.equals(c.factsToken()) ||
            !Objects.equals(previousId,c.oldId()) ||
            !Objects.equals(previousStatus,c.oldStatus()) ||
            generation!=c.generation() ||
            (!requestHash.equals(sha("v1\n"+classId+"\n"+schedule+"\n"+status))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance event differs from original admitted Core claim");

        if("CONFIRMED".equals(c.state())) {
            if(!event.equals(c.eventId()) ||
                !request.payloadHash().equals(c.confirmedHash()) ||
                !Long.valueOf(version).equals(c.confirmedVersion()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core Attendance confirmed receipt has different event identity");
            String response=db.queryForObject(
                "SELECT response_payload::text FROM "+
                "teacher_attendance_native_reference_receipts "+
                "WHERE event_id=CAST(? AS UUID) AND claim_id=CAST(? AS UUID) "+
                "AND payload_hash=? AND version=?",
                String.class,event,claimId,request.payloadHash(),version);
            try {
                JsonNode payload=mapper.readTree(response);
                if(payload==null || !payload.isObject() ||
                    !schedule.equals(payload.path("teachingScheduleId").asText()))
                    throw new IllegalStateException("Committed native receipt corrupted");
                return new Receipt(event,schedule,version,request.payloadHash(),
                    "COMMITTED",payload);
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Committed native Attendance payload invalid",ex);
            }
        }
        if(!"OPEN".equals(c.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance claim state invalid");

        Modes modes=db.queryForObject(
            "SELECT a.writer_mode,a.generation,s.writer_mode,academic.writer_mode "+
            "FROM teacher_attendance_writer_control a "+
            "JOIN scheduling_assignment_writer_control s ON s.singleton=TRUE "+
            "JOIN academic_session_writer_control academic ON academic.singleton=TRUE "+
            "WHERE a.singleton=TRUE FOR SHARE OF a,s,academic",
            (rs,i)->new Modes(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getString(4)));
        if(modes==null || !"ATTENDANCE".equals(modes.attendance()) ||
            !"QUIESCED".equals(modes.scheduling()) ||
            !"QUIESCED".equals(modes.academic()) ||
            generation!=modes.generation())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Attendance reference cannot write outside exclusive owner");

        // Exact CS, assigned lesson and version token are rechecked even
        // after a network gap. Never rely solely on the old bearer.
        var fresh=facts.nativeForClaim(classId,schedule,actor,employeeId);
        if(!token.equals(fresh.token()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native reference Academic/CS facts have changed");

        List<Existing> old=db.query(
            "SELECT attendance_id,status,recorded_at FROM teacher_session_attendance "+
            "WHERE teaching_schedule_id=? FOR UPDATE",
            (rs,i)->new Existing(rs.getString(1),rs.getString(2),
                rs.getTimestamp(3).toInstant()),schedule);
        if(old.size()>1 ||
            !Objects.equals(old.isEmpty()?null:old.get(0).id(),previousId) ||
            !Objects.equals(old.isEmpty()?null:old.get(0).status(),previousStatus) ||
            (old.size()==1 && (!attendanceId.equals(old.get(0).id()) ||
                !recordedAt.equals(old.get(0).recordedAt()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Attendance reference baseline is not the admitted one");

        Long latest=db.queryForObject(
            "SELECT COALESCE(MAX(version),0) "+
            "FROM teacher_attendance_native_reference_receipts "+
            "WHERE teaching_schedule_id=?",Long.class,schedule);
        if(latest==null || latest+1!=version)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance reference revision is missing or reordered");

        // The original Core business writer trigger forbids this UPDATE.
        // The GUC is scoped to this transaction, and the trigger requires
        // an exact OPEN claim under the ATTENDANCE writer mode.
        db.queryForObject("SELECT set_config('iig.attendance_reference_projection',"+
            "'true',true)",String.class);
        db.queryForObject("SELECT set_config('iig.attendance_reference_claim',"+
            "?,true)",String.class,claimId);
        int changed;
        if(old.isEmpty()) {
            changed=db.update(
                "INSERT INTO teacher_session_attendance "+
                "(attendance_id,teaching_schedule_id,status,recorded_by,recorded_at,updated_at) "+
                "VALUES(?,?,?,?,?,?)",
                attendanceId,schedule,status,employeeId,
                Timestamp.from(recordedAt),Timestamp.from(updatedAt));
        } else {
            changed=db.update(
                "UPDATE teacher_session_attendance SET status=?,recorded_by=?,updated_at=? "+
                "WHERE teaching_schedule_id=? AND attendance_id=?",
                status,employeeId,Timestamp.from(updatedAt),schedule,attendanceId);
        }
        if(changed!=1)
            throw new IllegalStateException("Core Attendance reference not projected");
        var row=db.queryForMap(
            "SELECT a.attendance_id AS id,"+
            "a.teaching_schedule_id AS \"teachingScheduleId\","+
            "a.status,a.recorded_at AS \"recordedAt\","+
            "a.updated_at AS \"updatedAt\","+
            "e.full_name AS \"recordedByName\" "+
            "FROM teacher_session_attendance a JOIN employees e "+
            "ON e.employee_id=a.recorded_by "+
            "WHERE a.teaching_schedule_id=?",
            schedule);
        JsonNode response=mapper.valueToTree(row);
        if(!schedule.equals(response.path("teachingScheduleId").asText()) ||
            !status.equals(response.path("status").asText()))
            throw new IllegalStateException("Core Attendance projected response mismatch");
        int stored=db.update(
            "INSERT INTO teacher_attendance_native_reference_receipts "+
            "(event_id,claim_id,actor_username,request_key,teaching_schedule_id,"+
            "version,payload_hash,attendance_id,status,recorded_by,response_payload) "+
            "VALUES(CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?,?,CAST(? AS JSONB))",
            event,claimId,actor,requestKey,schedule,version,
            request.payloadHash(),attendanceId,status,employeeId,response.toString());
        if(stored!=1)
            throw new IllegalStateException("Core Attendance immutable receipt missing");
        access.audit(new SchedulingAccess.StaffIdentity(
            employeeId,actor,"CS"),"RECORD_TEACHER_ATTENDANCE_"+status,
            "TEACHING_SCHEDULE",schedule);
        int finished=db.update(
            "UPDATE teacher_attendance_native_claims SET state='CONFIRMED',"+
            "confirmed_event=CAST(? AS UUID),confirmed_hash=?,"+
            "confirmed_version=?,confirmed_at=NOW() "+
            "WHERE claim_id=CAST(? AS UUID) AND state='OPEN'",
            event,request.payloadHash(),version,claimId);
        if(finished!=1)
            throw new IllegalStateException("Core Attendance claim finality failed");
        return new Receipt(event,schedule,version,request.payloadHash(),
            "COMMITTED",response);
    }
}

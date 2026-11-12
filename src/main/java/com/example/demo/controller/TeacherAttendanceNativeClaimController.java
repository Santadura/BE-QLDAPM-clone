package com.example.demo.controller;

import com.example.demo.service.SchedulingAccess;
import com.example.demo.service.TeacherAttendanceLiveFactsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * CORE-side exclusive admission for an independent Attendance transaction.
 * This never records business attendance: it reserves one immutable
 * actor/key/schedule intent under the ATTENDANCE writer generation.
 *
 * All Core ordinary teacher-attendance PUTs remain SQL-fenced while
 * ATTENDANCE owns the writer. OPEN claims prevent automatic writer handback.
 * Explicitly disabled until native ownership is audited and activated.
 */
@RestController
@RequestMapping("/internal/attendance/native/claims")
@ConditionalOnProperty(name="iig.attendance.native-reference-enabled",
    havingValue="true")
public class TeacherAttendanceNativeClaimController {
    public record Request(String classId,String teachingScheduleId,
        String requestKey,String requestHash,String status,String factsToken) {}
    public record Claim(String coreClaimId,String actorUsername,
        String actorEmployeeId,String classId,String teachingScheduleId,
        String requestKey,String requestHash,String status,String factsToken,
        long writerGeneration,String previousAttendanceId,String previousStatus,
        String ownerMode,String state) {}
    private record Prior(String attendanceId,String status) {}
    private final JdbcTemplate db;
    private final SchedulingAccess access;
    private final TeacherAttendanceLiveFactsService facts;
    private final byte[] internalKey;

    public TeacherAttendanceNativeClaimController(JdbcTemplate db,
            SchedulingAccess access,TeacherAttendanceLiveFactsService facts,
            @Value("${iig.attendance.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Private Attendance admission key required");
        this.db=db;this.access=access;this.facts=facts;
        internalKey=secret.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean safe(String text,int max) {
        return text!=null && text.length()<=max &&
            text.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }

    static String digest(String classId,String scheduleId,String status) {
        try {
            String body="v1\n"+classId+"\n"+scheduleId+"\n"+status;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    private void authorize(String supplied) {
        if(!MessageDigest.isEqual(internalKey,
            (supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid native Attendance service key");
    }

    @PostMapping
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Claim acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestBody Request request) {
        authorize(key);
        if(request==null || !safe(request.classId(),50) ||
            !safe(request.teachingScheduleId(),50) || !safe(request.requestKey(),128) ||
            !Set.of("PRESENT","ABSENT","LATE").contains(request.status()) ||
            request.requestHash()==null ||
            !request.requestHash().equals(digest(
                request.classId(),request.teachingScheduleId(),request.status())) ||
            request.factsToken()==null ||
            !request.factsToken().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact normalized Attendance intent, key and source token required");

        // The same current CS JWT, employee status, support shift and
        // Academic/Scheduling version proof as the original Core writer.
        var actor=access.requireRole("CS");
        var now=facts.nativeCurrent(request.classId(),request.teachingScheduleId());
        if(!MessageDigest.isEqual(
            now.token().getBytes(StandardCharsets.US_ASCII),
            request.factsToken().getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic lesson or exact CS support changed during native admission");

        List<Prior> previous=db.query(
            "SELECT attendance_id,status FROM teacher_session_attendance "+
            "WHERE teaching_schedule_id=? FOR SHARE",
            (rs,i)->new Prior(rs.getString(1),rs.getString(2)),
            request.teachingScheduleId());
        if(previous.size()>1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate Core attendance source schedule");
        Prior prior=previous.isEmpty()?new Prior(null,null):previous.get(0);
        String uuid=UUID.randomUUID().toString();
        try {
            db.update(
                "INSERT INTO teacher_attendance_native_claims "+
                "(claim_id,actor_username,actor_employee_id,class_id,"+
                "teaching_schedule_id,request_key,request_hash,attendance_status,"+
                "previous_attendance_id,previous_status,facts_token,writer_generation) "+
                "VALUES(CAST(? AS UUID),?,?,?,?,?,?,?,?,?,?,?) "+
                "ON CONFLICT(actor_username,class_id,teaching_schedule_id,request_key) "+
                "DO NOTHING",
                uuid,actor.username(),actor.id(),request.classId(),
                request.teachingScheduleId(),request.requestKey(),request.requestHash(),
                request.status(),prior.attendanceId(),prior.status(),
                now.token(),now.attendanceGeneration());
        } catch(DataIntegrityViolationException ex) {
            // The partial unique index permits ONLY one OPEN claim per
            // schedule, even when two different operators issue new keys.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another native Attendance transaction already owns this schedule",ex);
        }
        List<Claim> receipts=db.query(
            "SELECT claim_id::text,actor_username,actor_employee_id,class_id,"+
            "teaching_schedule_id,request_key,request_hash,attendance_status,"+
            "facts_token,writer_generation,previous_attendance_id,previous_status,state "+
            "FROM teacher_attendance_native_claims "+
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? "+
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Claim(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getString(9),rs.getLong(10),
                rs.getString(11),rs.getString(12),"ATTENDANCE",rs.getString(13)),
            actor.username(),request.classId(),request.teachingScheduleId(),
            request.requestKey());
        if(receipts.size()!=1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Native Attendance command claim disappeared");
        Claim claim=receipts.get(0);
        if(!request.requestHash().equals(claim.requestHash()) ||
            !request.status().equals(claim.status()) ||
            !request.factsToken().equals(claim.factsToken()) ||
            !actor.id().equals(claim.actorEmployeeId()) ||
            claim.writerGeneration()!=now.attendanceGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance idempotency key reused with changed proof");
        return claim;
    }
    /**
     * Core claim may commit and the Attendance JVM may crash BEFORE its
     * first local transaction. This bounded private cursor is the only
     * authorized source from which a worker may reconstruct that exact
     * committed intent. Every candidate is rechecked against the CURRENT
     * employee role and original Academic/CS lesson generation: stale or
     * revoked proofs remain OPEN for operator intervention, never guessed.
     *
     * All returned fields are immutable and the endpoint never modifies a
     * Core domain row or alters claim state.
     */
    @GetMapping("/recoverable")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Claim> recoverable(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam(defaultValue="25") int limit) {
        authorize(key);
        if(limit<1 || limit>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Core native claim scan must be bounded to 1..50");
        var raw=db.query(
            "SELECT c.claim_id::text,c.actor_username,c.actor_employee_id,"+
            "c.class_id,c.teaching_schedule_id,c.request_key,c.request_hash,"+
            "c.attendance_status,c.facts_token,c.writer_generation,"+
            "c.previous_attendance_id,c.previous_status,c.state "+
            "FROM teacher_attendance_native_claims c "+
            "JOIN teacher_attendance_writer_control w ON w.singleton=TRUE "+
            "JOIN scheduling_assignment_writer_control s ON s.singleton=TRUE "+
            "JOIN academic_session_writer_control a ON a.singleton=TRUE "+
            "WHERE c.state='OPEN' AND w.writer_mode='ATTENDANCE' "+
            "AND c.writer_generation=w.generation "+
            "AND s.writer_mode='QUIESCED' AND a.writer_mode='QUIESCED' "+
            "ORDER BY c.created_at,c.claim_id LIMIT ?",
            (rs,i)->new Claim(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getLong(10),rs.getString(11),rs.getString(12),
                "ATTENDANCE",rs.getString(13)),limit);
        var admissible=new java.util.ArrayList<Claim>();
        for(var candidate:raw) {
            try {
                var current=facts.nativeForClaim(candidate.classId(),
                    candidate.teachingScheduleId(),candidate.actorUsername(),
                    candidate.actorEmployeeId());
                if(candidate.factsToken().equals(current.token()) &&
                   candidate.writerGeneration()==current.attendanceGeneration())
                    admissible.add(candidate);
            } catch(com.example.demo.exception.ApiException ex) {
                // Revoked actor, altered schedule or missing support => remain
                // in Core OPEN; do not tell the worker to make a business write.
            }
        }
        return List.copyOf(admissible);
    }

}

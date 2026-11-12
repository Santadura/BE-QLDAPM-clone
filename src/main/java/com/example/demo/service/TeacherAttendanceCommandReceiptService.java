package com.example.demo.service;

import com.example.demo.exception.ApiException;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Core receipt for the private Attendance PUT bridge.
 * A duplicate command must revalidate current CS authority without recording
 * attendance or emitting an extra audit/outbox event.
 */
@Service
public class TeacherAttendanceCommandReceiptService {
    private static final Pattern SAFE_KEY=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final TeacherSessionAttendanceService attendance;
    private final TeacherAttendanceLiveFactsService liveFacts;
    private final SchedulingAccess access;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public TeacherAttendanceCommandReceiptService(TeacherSessionAttendanceService attendance,
            SchedulingAccess access,JdbcTemplate db,ObjectMapper mapper,
            TeacherAttendanceLiveFactsService liveFacts) {
        this.attendance=attendance;
        this.access=access;
        this.db=db;
        this.mapper=mapper;
        this.liveFacts=liveFacts;
    }

    static String fingerprint(String classId,String scheduleId,String status) {
        try {
            String canonical="v1\n"+classId+"\n"+scheduleId+"\n"+status;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    public record ReceiptQuery(String actorUsername,String classId,
        String teachingScheduleId,String requestKey,String requestHash) {}
    public record CommittedReceipt(String actorUsername,String classId,
        String teachingScheduleId,String requestKey,String requestHash,
        JsonNode response) {}

    /**
     * Safe service-to-service outcome reconciliation. Reading the exact
     * committed Core transaction is NOT a mutation retry and never acts
     * under a revoked CS user's old bearer. The response remains private.
     */
    @Transactional(readOnly=true)
    public CommittedReceipt committedReceipt(ReceiptQuery q) {
        if(q==null || q.actorUsername()==null || q.classId()==null ||
            q.teachingScheduleId()==null || q.requestKey()==null ||
            q.requestHash()==null ||
            !q.actorUsername().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !q.classId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !q.teachingScheduleId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !SAFE_KEY.matcher(q.requestKey()).matches() ||
            !q.requestHash().matches("[0-9a-f]{64}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Exact committed Core attendance receipt identity required");
        var rows=db.queryForList(
            "SELECT request_hash,response_payload::text AS payload "+
            "FROM teacher_attendance_command_receipts "+
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? "+
            "AND request_key=? AND response_payload IS NOT NULL AND completed_at IS NOT NULL",
            q.actorUsername(),q.classId(),q.teachingScheduleId(),q.requestKey());
        if(rows.size()!=1)
            throw new ApiException(HttpStatus.NOT_FOUND,
                "Core attendance transaction not confirmed; leave remote inbox pending");
        String hash=String.valueOf(rows.get(0).get("request_hash"));
        if(!q.requestHash().equals(hash))
            throw new ApiException(HttpStatus.CONFLICT,
                "Core attendance receipt digest changed or belongs to another request");
        try {
            JsonNode response=mapper.readTree(String.valueOf(rows.get(0).get("payload")));
            if(response==null || !response.isObject() ||
                !q.teachingScheduleId().equals(response.path("teachingScheduleId").asText()))
                throw new ApiException(HttpStatus.CONFLICT,
                    "Core committed attendance payload is invalid");
            return new CommittedReceipt(q.actorUsername(),q.classId(),
                q.teachingScheduleId(),q.requestKey(),q.requestHash(),response);
        } catch(JsonProcessingException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Corrupt committed Core attendance receipt");
        }
    }

    @Transactional
    public Map<String,Object> mark(String classId,String scheduleId,String requestKey,
            String expectedFactsToken,MarkRequest request) {
        // Current JWT + active employee CS role is checked on EVERY attempt.
        var actor=access.requireRole("CS");
        String status=TeacherSessionAttendanceService.checkedStatus(
            request==null?null:request.status());
        if(requestKey==null) {
            Map<String,Object> result=attendance.mark(classId,scheduleId,new MarkRequest(status));
            liveFacts.requireUnchanged(classId,scheduleId,expectedFactsToken);
            return result;
        }
        if(!SAFE_KEY.matcher(requestKey).matches())
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 1-128 safe ASCII characters");
        String hash=fingerprint(classId,scheduleId,status);
        db.update("INSERT INTO teacher_attendance_command_receipts " +
            "(actor_username,class_id,teaching_schedule_id,request_key,request_hash) " +
            "VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
            actor.username(),classId,scheduleId,requestKey,hash);

        // PRIMARY KEY insert conflict serializes concurrent retries; the
        // following row lock sees the committed receipt or a clean retry.
        Map<String,Object> row=db.queryForMap(
            "SELECT request_hash,response_payload::text AS response_payload " +
            "FROM teacher_attendance_command_receipts " +
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? " +
            "AND request_key=? FOR UPDATE",
            actor.username(),classId,scheduleId,requestKey);
        if(!hash.equals(row.get("request_hash")))
            throw new ApiException(HttpStatus.CONFLICT,
                "Idempotency-Key reused for a different attendance status");

        Object payload=row.get("response_payload");
        if(payload!=null) {
            // The caller can no longer obtain an old receipt after losing
            // the exact active CS support shift, even if the key is correct.
            attendance.requireCurrentSupportForReplay(classId,scheduleId,actor.id());
            try {
                return mapper.readValue(String.valueOf(payload),
                    new TypeReference<Map<String,Object>>(){});
            } catch(JsonProcessingException ex) {
                throw new IllegalStateException("Corrupted Attendance Core receipt",ex);
            }
        }

        Map<String,Object> result=attendance.mark(classId,scheduleId,new MarkRequest(status));
        // Original mark() held class, lesson, teacher and exact CS row locks.
        // This validation is inside the SAME transaction, so a changed
        // Academic/Scheduling version rolls back attendance, audit and outbox.
        liveFacts.requireUnchanged(classId,scheduleId,expectedFactsToken);
        try {
            int changed=db.update("UPDATE teacher_attendance_command_receipts " +
                "SET response_payload=CAST(? AS JSONB),completed_at=NOW() " +
                "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? " +
                "AND request_key=? AND response_payload IS NULL",
                mapper.writeValueAsString(result),actor.username(),classId,scheduleId,requestKey);
            if(changed!=1)throw new IllegalStateException("Attendance Core receipt write lost");
        } catch(JsonProcessingException ex) {
            throw new IllegalStateException("Attendance response serialization failed",ex);
        }
        return result;
    }
}

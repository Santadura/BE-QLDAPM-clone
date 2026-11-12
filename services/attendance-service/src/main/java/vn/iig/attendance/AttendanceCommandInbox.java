package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent Attendance inbox. PENDING survives ambiguous Core HTTP timeouts
 * so the same request key can be used again. This is NOT a second authority
 * for teacher attendance: Core's atomic receipt decides the outcome.
 */
@Repository
public class AttendanceCommandInbox {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AttendanceCommandInbox(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db;
        this.mapper=mapper;
    }

    public record Pending(String actor,String classId,String scheduleId,
        String requestKey,String requestHash) {}

    /** Bounded, durable cursor for a restart-safe, never-blind reconciliation. */
    public List<Pending> pending(int limit) {
        if(limit<1 || limit>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance recovery scan limit must be 1..50");
        return db.query(
            "SELECT actor_username,class_id,teaching_schedule_id,request_key,request_hash "+
            "FROM attendance_write_command_inbox WHERE state='PENDING' "+
            "ORDER BY created_at,actor_username,request_key LIMIT ?",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5)),
            limit);
    }

    /**
     * Cutover safety barrier: PENDING is an ambiguous Core transaction, NOT
     * an absent attendance mutation. A missed response must be reconciled
     * with the original idempotency key before independent ownership begins.
     */
    public long pendingCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_write_command_inbox WHERE state='PENDING'",
            Long.class);
        if(count==null || count<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Independent Attendance pending command ledger unavailable");
        return count;
    }

    @Transactional
    public void stage(String actor,String classId,String scheduleId,String key,String hash) {
        db.update("INSERT INTO attendance_write_command_inbox " +
            "(actor_username,class_id,teaching_schedule_id,request_key,request_hash) " +
            "VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
            actor,classId,scheduleId,key,hash);
        Map<String,Object> current=db.queryForMap(
            "SELECT request_hash FROM attendance_write_command_inbox " +
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? " +
            "AND request_key=? FOR UPDATE",
            actor,classId,scheduleId,key);
        if(!hash.equals(current.get("request_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key reused for a different attendance command");
    }

    @Transactional
    public void complete(String actor,String classId,String scheduleId,String key,
            String hash,JsonNode result) {
        if(result==null || !result.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Core Attendance command response");
        int changed=db.update("UPDATE attendance_write_command_inbox " +
            "SET state='COMMITTED',response_payload=CAST(? AS JSONB),completed_at=NOW() " +
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? " +
            "AND request_key=? AND request_hash=? AND state='PENDING'",
            result.toString(),actor,classId,scheduleId,key,hash);
        if(changed==1)return;
        // Concurrent callers of the same command may both reach Core. It
        // returns one outcome, and local completion must never diverge.
        Map<String,Object> existing=db.queryForMap(
            "SELECT request_hash,response_payload::text AS response_payload " +
            "FROM attendance_write_command_inbox " +
            "WHERE actor_username=? AND class_id=? AND teaching_schedule_id=? " +
            "AND request_key=? FOR UPDATE",
            actor,classId,scheduleId,key);
        try {
            JsonNode stored=mapper.readTree(String.valueOf(existing.get("response_payload")));
            if(!hash.equals(existing.get("request_hash")) || !result.equals(stored))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Attendance inbox result differs from the authoritative Core receipt");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Corrupted Attendance inbox receipt",ex);
        }
    }
}

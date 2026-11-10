package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only durable Core acknowledgement lookup for manually reconciling
 * Scheduling relay timeouts. Does not permit reference writes, version resets
 * or any assignment mutation. Available independently of the write toggle.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reference/receipt")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingAssignmentReferenceReceiptController {
    public record Receipt(String eventId,long revision,String payloadHash) {}

    private final JdbcTemplate db;
    private final byte[] internalKey;

    public SchedulingAssignmentReferenceReceiptController(
            JdbcTemplate db,@Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.internalKey=key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Exact historical receipt for a single immutable event. Unlike the
     * latest-per-schedule view this can validate an old, already-applied
     * event after later revisions advanced the Core reference.
     */
    @GetMapping("/event/{eventId}")
    public Receipt lookupEvent(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @PathVariable String eventId,
            @RequestParam String sourceType,@RequestParam String scheduleId) {
        authorize(key);
        validateSource(sourceType,scheduleId);
        if(eventId==null || eventId.isBlank() || eventId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid immutable Core event identifier");
        List<Receipt> rows=db.query(
            "SELECT event_id,revision,payload_hash " +
            "FROM scheduling_assignment_reference_event_receipts " +
            "WHERE event_id=? AND source_type=? AND schedule_id=?",
            (rs,i)->new Receipt(rs.getString(1),rs.getLong(2),rs.getString(3)),
            eventId,sourceType,scheduleId);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No immutable Core receipt for this event");
        if("TEACHING".equals(sourceType)) {
            // A historical event receipt by itself is insufficient proof
            // if the Core teacher row was deleted or its authorizing TC
            // writer claim was never confirmed. These three facts must
            // still agree before an exhausted Scheduling outbox is marked
            // COMMITTED in manual recovery.
            Long live=db.queryForObject(
                "SELECT count(*) FROM teaching_schedules t " +
                "JOIN scheduling_assignment_reference_event_receipts e " +
                "ON e.source_type='TEACHING' AND e.schedule_id=t.teaching_schedule_id " +
                "AND e.event_id=? AND e.operation='ASSIGN_TEACHER' " +
                "JOIN scheduling_assignment_writer_claims c ON " +
                "c.actor_username=e.actor_username AND c.operation=e.operation " +
                "AND c.request_key=e.request_key AND c.manager_employee_id=t.assigned_by " +
                "AND c.status='CONFIRMED' " +
                "WHERE t.teaching_schedule_id=? AND t.status='ASSIGNED'",
                Long.class,eventId,scheduleId);
            if(live==null || live!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Immutable teacher receipt, Core reference and TC claim diverged");
        }
        return rows.get(0);
    }

    private void authorize(String key) {
        byte[] supplied=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(internalKey,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling reference lookup key");
    }

    private void validateSource(String sourceType,String scheduleId) {
        if(!"STAFF".equals(sourceType) && !"TEACHING".equals(sourceType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown reference source type");
        if(scheduleId==null || scheduleId.isBlank() || scheduleId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid reference schedule ID");
    }

    @GetMapping
    public Receipt lookup(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam String sourceType,@RequestParam String scheduleId) {
        authorize(key);
        validateSource(sourceType,scheduleId);

        List<Receipt> rows=db.query(
            "SELECT last_event_id,last_revision,payload_hash " +
            "FROM scheduling_assignment_reference_receipts " +
            "WHERE source_type=? AND schedule_id=?",
            (rs,i)->new Receipt(rs.getString(1),rs.getLong(2),rs.getString(3)),
            sourceType,scheduleId);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No committed Core reference receipt for this schedule");
        return rows.get(0);
    }
}

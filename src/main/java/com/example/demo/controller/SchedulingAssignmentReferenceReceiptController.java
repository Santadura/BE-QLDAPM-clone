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

    @GetMapping
    public Receipt lookup(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam String sourceType,@RequestParam String scheduleId) {
        byte[] supplied=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(internalKey,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling reference lookup key");
        if(!"STAFF".equals(sourceType) && !"TEACHING".equals(sourceType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown reference source type");
        if(scheduleId==null || scheduleId.isBlank() || scheduleId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid reference schedule ID");

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

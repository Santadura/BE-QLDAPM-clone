package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private, read-only Core student and class membership event feed.
 * Current Core writers continue unchanged. No PII is routed via Gateway.
 */
@RestController
@RequestMapping("/internal/academic/student-roster/events")
@ConditionalOnProperty(name="iig.academic.bridge-enabled",havingValue="true")
public class AcademicStudentRosterSourceBridge {
    public record Event(long eventId,String entityType,String entityId,
        String eventKind,long sourceRevision,String payloadJson,String payloadHash) {}
    public record Summary(long sourceEvents,long lastEventId,long studentRows,
        long membershipRows,long distinctEntities) {}

    private final JdbcTemplate db;
    private final byte[] key;
    public AcademicStudentRosterSourceBridge(JdbcTemplate db,
            @Value("${iig.academic.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Academic roster internal key required");
        db.getClass(); // enforce non-null dependency before accepting requests
        this.db=db;this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    private void requireKey(String supplied) {
        byte[] candidate=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Core student roster service key required");
    }
    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }

    /** Monotonic event IDs; the source clock serializes writers at COMMIT. */
    @GetMapping("/history")
    @Transactional(readOnly=true)
    public List<Event> history(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="100") int limit) {
        requireKey(supplied);
        if(after<0 || limit<1 || limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic roster source cursor and page must be bounded");
        return db.query(
            "SELECT event_id,entity_type,entity_id,event_kind,source_revision,"+
            "payload::text FROM academic_roster_source_outbox "+
            "WHERE event_id>? ORDER BY event_id LIMIT ?",
            (rs,i)->{
                String payload=rs.getString(6);
                return new Event(rs.getLong(1),rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getLong(5),payload,sha256(payload));
            },after,limit);
    }

    @GetMapping("/summary")
    @Transactional(readOnly=true)
    public Summary summary(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT (SELECT COUNT(*) FROM academic_roster_source_outbox),"+
            "(SELECT COALESCE(MAX(event_id),0) FROM academic_roster_source_outbox),"+
            "(SELECT COUNT(*) FROM students),"+
            "(SELECT COUNT(*) FROM class_students),"+
            "(SELECT COUNT(*) FROM (SELECT DISTINCT entity_type,entity_id "+
            "FROM academic_roster_source_outbox) x)",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5)));
    }
}

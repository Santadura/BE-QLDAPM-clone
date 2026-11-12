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

    /**
     * Independent Core source-integrity proof. A current Core record must
     * appear as the latest immutable UPSERT (including its entire JSON);
     * a tombstone may never point to a still-live entity. This detects
     * missed triggers and unauthorized post-event updates, not only counts.
     */
    public record Integrity(long studentRows,long membershipRows,
        long newestStudentRows,long newestMembershipRows,long divergentRows) {}

    @GetMapping("/integrity")
    @Transactional(readOnly=true)
    public Integrity integrity(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "WITH newest AS ("+
            " SELECT DISTINCT ON(entity_type,entity_id) entity_type,entity_id,"+
            " event_kind,payload FROM academic_roster_source_outbox "+
            " ORDER BY entity_type,entity_id,event_id DESC), "+
            " mismatches AS (SELECT COUNT(*) AS n FROM newest e "+
            " LEFT JOIN students s ON e.entity_type='STUDENT' AND s.student_id=e.entity_id "+
            " LEFT JOIN class_students m ON e.entity_type='MEMBERSHIP' AND m.class_student_id=e.entity_id "+
            " LEFT JOIN student_targets t ON e.entity_type='TARGET' AND t.student_target_id=e.entity_id "+
            " LEFT JOIN student_results r ON e.entity_type='RESULT' AND r.student_result_id=e.entity_id "+
            " WHERE (e.entity_type='STUDENT' AND "+
            " ((e.event_kind='UPSERT' AND (s.student_id IS NULL OR e.payload IS DISTINCT FROM to_jsonb(s))) "+
            " OR (e.event_kind='DELETE' AND s.student_id IS NOT NULL))) "+
            " OR (e.entity_type='MEMBERSHIP' AND "+
            " ((e.event_kind='UPSERT' AND (m.class_student_id IS NULL OR e.payload IS DISTINCT FROM to_jsonb(m))) "+
            " OR (e.event_kind='DELETE' AND m.class_student_id IS NOT NULL))) "+
            " OR (e.entity_type='TARGET' AND "+
            " ((e.event_kind='UPSERT' AND (t.student_target_id IS NULL OR e.payload IS DISTINCT FROM to_jsonb(t))) "+
            " OR (e.event_kind='DELETE' AND t.student_target_id IS NOT NULL))) "+
            " OR (e.entity_type='RESULT' AND "+
            " ((e.event_kind='UPSERT' AND (r.student_result_id IS NULL OR e.payload IS DISTINCT FROM to_jsonb(r))) "+
            " OR (e.event_kind='DELETE' AND r.student_result_id IS NOT NULL)))) "+
            " SELECT (SELECT COUNT(*) FROM students),"+
            " (SELECT COUNT(*) FROM class_students),"+
            " (SELECT COUNT(*) FROM newest WHERE entity_type='STUDENT' AND event_kind='UPSERT'),"+
            " (SELECT COUNT(*) FROM newest WHERE entity_type='MEMBERSHIP' AND event_kind='UPSERT'),"+
            " (SELECT n FROM mismatches) + "+
            " ABS((SELECT COUNT(*) FROM student_targets) - "+
            " (SELECT COUNT(*) FROM newest WHERE entity_type='TARGET' AND event_kind='UPSERT')) + "+
            " ABS((SELECT COUNT(*) FROM student_results) - "+
            " (SELECT COUNT(*) FROM newest WHERE entity_type='RESULT' AND event_kind='UPSERT'))",
            (rs,i)->new Integrity(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5)));
    }

    /**
     * Database-enforced Core student/roster writer mode. A frozen snapshot
     * alone does NOT transfer write ownership or authorize public writes.
     */
    public record WriterFence(String mode,long generation) {}

    @GetMapping("/fence")
    @Transactional(readOnly=true)
    public WriterFence writerFence(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT writer_mode,generation FROM academic_roster_writer_control "+
            "WHERE singleton=TRUE",
            (rs,i)->new WriterFence(rs.getString(1),rs.getLong(2)));
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

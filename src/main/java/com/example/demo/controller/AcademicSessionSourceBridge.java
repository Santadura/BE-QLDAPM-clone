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
 * Immutable Core class-session source history, including DELETE tombstones.
 * Academic acknowledges only after its independent PostgreSQL commit.
 * This is a private data feed, NOT an independent Academic writer.
 */
@RestController
@RequestMapping("/internal/academic/sessions/events")
@ConditionalOnProperty(name="iig.academic.bridge-enabled",havingValue="true")
public class AcademicSessionSourceBridge {
    public record Event(long eventId,String classSessionId,String classId,
        String eventKind,long sourceRevision,String payloadJson,String payloadHash) {}
    public record Ack(long eventId,String payloadHash) {}
    public record Summary(long sourceRows,long eventCount,long pendingEvents,long maxEventId) {}

    private final JdbcTemplate db;
    private final byte[] key;

    public AcademicSessionSourceBridge(JdbcTemplate db,
            @Value("${iig.academic.internal-key}") String key) {
        if(key==null || key.isBlank())throw new IllegalArgumentException("Academic key required");
        this.db=db;this.key=key.getBytes(StandardCharsets.UTF_8);
    }

    private void requireKey(String supplied) {
        byte[] bytes=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,bytes))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Academic event key");
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
    private static void validate(int limit) {
        if(limit<1 || limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic source event batch must contain 1..100 events");
    }
    private List<Event> read(String sql,Object... params) {
        return db.query(sql,(rs,i)->{
            String payload=rs.getString(6);
            return new Event(rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getLong(5),payload,hash(payload));
        },params);
    }

    @GetMapping
    @Transactional(readOnly=true)
    public List<Event> pending(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestParam(defaultValue="100") int limit) {
        requireKey(supplied);validate(limit);
        return read("SELECT event_id,class_session_id,class_id,event_kind,"+
            "source_revision,payload::text FROM academic_session_source_outbox "+
            "WHERE delivered_at IS NULL ORDER BY event_id LIMIT ?",limit);
    }

    /** Includes previously acknowledged events and the initial backfill. */
    @GetMapping("/history")
    @Transactional(readOnly=true)
    public List<Event> history(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="100") int limit) {
        requireKey(supplied);validate(limit);
        if(after<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid cursor");
        return read("SELECT event_id,class_session_id,class_id,event_kind,"+
            "source_revision,payload::text FROM academic_session_source_outbox "+
            "WHERE event_id>? ORDER BY event_id LIMIT ?",after,limit);
    }

    public record WriterFence(String academicMode,long academicGeneration,
                              String attendanceMode,long attendanceGeneration,
                              String schedulingMode,long schedulingGeneration) {}

    @GetMapping("/fence")
    @Transactional(readOnly=true)
    public WriterFence writerFence(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT a.writer_mode,a.generation,t.writer_mode,t.generation,"+
            "s.writer_mode,s.writer_generation "+
            "FROM academic_session_writer_control a "+
            "JOIN teacher_attendance_writer_control t ON t.singleton=TRUE "+
            "JOIN scheduling_assignment_writer_control s ON s.singleton=TRUE "+
            "WHERE a.singleton=TRUE",
            (rs,i)->new WriterFence(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getLong(4),rs.getString(5),rs.getLong(6)));
    }

    @GetMapping("/summary")
    @Transactional(readOnly=true)
    public Summary summary(@RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT (SELECT COUNT(*) FROM class_sessions),"+
            "(SELECT COUNT(*) FROM academic_session_source_outbox),"+
            "(SELECT COUNT(*) FROM academic_session_source_outbox WHERE delivered_at IS NULL),"+
            "(SELECT COALESCE(MAX(event_id),0) FROM academic_session_source_outbox)",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),
                rs.getLong(3),rs.getLong(4)));
    }

    /**
     * Cutover evidence is read-only: verify that every current Core lesson
     * has a source event and that the newest event equals its actual row,
     * including deleted lessons (tombstones). This endpoint does NOT freeze
     * writers or transfer ownership.
     */
    public record Integrity(long sourceRows,long trackedLiveRows,long eventCount,
        long pendingEvents,long maxEventId,long distinctSessions,long divergentRows) {}

    @GetMapping("/integrity")
    @Transactional(readOnly=true)
    public Integrity integrity(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT "+
            "(SELECT COUNT(*) FROM class_sessions),"+
            "(SELECT COUNT(*) FROM class_sessions s WHERE EXISTS "+
            " (SELECT 1 FROM academic_session_source_outbox e "+
            "  WHERE e.class_session_id=s.class_session_id)),"+
            "(SELECT COUNT(*) FROM academic_session_source_outbox),"+
            "(SELECT COUNT(*) FROM academic_session_source_outbox WHERE delivered_at IS NULL),"+
            "(SELECT COALESCE(MAX(event_id),0) FROM academic_session_source_outbox),"+
            "(SELECT COUNT(DISTINCT class_session_id) FROM academic_session_source_outbox),"+
            "(SELECT COUNT(*) FROM ("+
            " SELECT DISTINCT ON (class_session_id) class_session_id,event_kind,payload "+
            " FROM academic_session_source_outbox "+
            " ORDER BY class_session_id,event_id DESC) newest "+
            " LEFT JOIN class_sessions s ON s.class_session_id=newest.class_session_id "+
            " WHERE (newest.event_kind='UPSERT' AND "+
            "  (s.class_session_id IS NULL OR newest.payload IS DISTINCT FROM to_jsonb(s))) "+
            " OR (newest.event_kind='DELETE' AND s.class_session_id IS NOT NULL))",
            (rs,i)->new Integrity(rs.getLong(1),rs.getLong(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7)));
    }

    @PostMapping("/ack")
    @Transactional
    public Ack acknowledge(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody Ack ack) {
        requireKey(supplied);
        if(ack==null || ack.eventId()<1 || ack.payloadHash()==null ||
                !ack.payloadHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Academic receipt");
        List<String> stored=db.query(
            "SELECT payload::text FROM academic_session_source_outbox "+
            "WHERE event_id=? FOR UPDATE",
            (rs,i)->rs.getString(1),ack.eventId());
        if(stored.size()!=1 || !hash(stored.get(0)).equals(ack.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic event digest differs from receipt");
        db.update("UPDATE academic_session_source_outbox "+
            "SET delivered_at=COALESCE(delivered_at,NOW()) WHERE event_id=?",ack.eventId());
        return ack;
    }
}

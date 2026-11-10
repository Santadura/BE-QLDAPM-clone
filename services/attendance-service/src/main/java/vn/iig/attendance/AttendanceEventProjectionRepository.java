package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core source events become independently committed Attendance-owned rows.
 * A retry with the same ID+digest is safe. A newer per-schedule source event
 * cannot be overwritten by a delayed earlier delivery.
 *
 * Apply commits before the HTTP acknowledgement is sent to Core; there is
 * deliberately no distributed JDBC transaction or unsafe "ack on request".
 */
@Repository
public class AttendanceEventProjectionRepository {
    private static final Set<String> STATUS=Set.of("PRESENT","ABSENT","LATE");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AttendanceEventProjectionRepository(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db;this.mapper=mapper;
    }

    /**
     * Attendance's own latest committed source status, including a
     * tombstone. Null return means no attendance has ever been recorded.
     * The live Core class roster is still required for CS authorization.
     */
    public record AttendanceState(String eventKind,String status) {}

    public AttendanceState stateFor(String scheduleId) {
        List<AttendanceState> matches=db.query(
            "SELECT event_kind,payload->>'status' " +
            "FROM attendance_teacher_source_records WHERE teaching_schedule_id=?",
            (rs,i)->new AttendanceState(rs.getString(1),rs.getString(2)),
            scheduleId);
        if(matches.size()>1)
            throw new IllegalStateException("Duplicate Attendance source projection");
        return matches.isEmpty()?null:matches.get(0);
    }


    public long projectedRowCount() {
        Long rows=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_source_records",Long.class);
        if(rows==null || rows<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance projection count unavailable");
        return rows;
    }

    /**
     * Compare complete versioned JSON, not merely the FE attendance status.
     * Deleted rows are retained as tombstones and must match exactly.
     */
    public boolean matchesManifest(AttendanceSourceClient.ManifestRow expected) {
        record Existing(long eventId,String kind,String payloadJson) {}
        List<Existing> stored=db.query(
            "SELECT source_event_id,event_kind,payload::text " +
            "FROM attendance_teacher_source_records WHERE teaching_schedule_id=?",
            (rs,i)->new Existing(rs.getLong(1),rs.getString(2),rs.getString(3)),
            expected.teachingScheduleId());
        if(stored.size()!=1) return false;
        Existing actual=stored.get(0);
        if(actual.eventId()!=expected.eventId() ||
                !actual.kind().equals(expected.eventKind())) return false;
        try {
            JsonNode left=mapper.readTree(actual.payloadJson());
            JsonNode right=mapper.readTree(expected.payloadJson());
            return left!=null && left.isObject() && left.equals(right)
                && expected.teachingScheduleId().equals(
                    left.path("teaching_schedule_id").asText());
        } catch(Exception ex) {
            return false;
        }
    }

    @Transactional
    public void apply(AttendanceSourceClient.Event event) {
        if(event==null || event.eventId()<1 ||
                event.teachingScheduleId()==null ||
                event.teachingScheduleId().isBlank() ||
                event.teachingScheduleId().length()>50 ||
                !Set.of("UPSERT","DELETE").contains(event.eventKind()) ||
                event.payloadJson()==null ||
                event.payloadHash()==null || !event.payloadHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Core Attendance event");
        String sha=sha256(event.payloadJson());
        if(!sha.equals(event.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Attendance event digest differs from its payload");

        final JsonNode json;
        try {
            json=mapper.readTree(event.payloadJson());
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Core Attendance event JSON malformed",ex);
        }
        if(json==null || !json.isObject() ||
                !event.teachingScheduleId().equals(
                    json.path("teaching_schedule_id").asText()) ||
                json.path("attendance_id").asText().isBlank() ||
                !STATUS.contains(json.path("status").asText()) ||
                json.path("recorded_by").asText().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Attendance event identity or status invalid");

        List<String> old=db.query(
            "SELECT payload_hash FROM attendance_import_receipts WHERE event_id=?",
            (rs,i)->rs.getString(1),event.eventId());
        if(!old.isEmpty()) {
            if(old.size()!=1 || !sha.equals(old.get(0)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Source event ID was reused with different Attendance data");
            return;
        }

        // For one teaching schedule, committed Core row mutations serialize,
        // so each newer event ID represents the next committed state.
        db.update(
            "INSERT INTO attendance_teacher_source_records " +
            "(teaching_schedule_id,source_event_id,event_kind,payload) " +
            "VALUES(?,?,?,CAST(? AS jsonb)) " +
            "ON CONFLICT(teaching_schedule_id) DO UPDATE SET " +
            "source_event_id=EXCLUDED.source_event_id," +
            "event_kind=EXCLUDED.event_kind,payload=EXCLUDED.payload," +
            "imported_at=NOW() WHERE " +
            "attendance_teacher_source_records.source_event_id<EXCLUDED.source_event_id",
            event.teachingScheduleId(),event.eventId(),event.eventKind(),
            event.payloadJson());

        db.update(
            "INSERT INTO attendance_import_receipts " +
            "(event_id,teaching_schedule_id,payload_hash) VALUES(?,?,?)",
            event.eventId(),event.teachingScheduleId(),sha);
    }

    private String sha256(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}

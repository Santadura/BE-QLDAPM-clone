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

    /** Separate ordered cursor so a late historical event cannot be missed. */
    public long historyCursor() {
        Long id=db.queryForObject(
            "SELECT last_event_id FROM attendance_history_backfill_cursor " +
            "WHERE singleton=TRUE",Long.class);
        if(id==null || id<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance historical cursor unavailable");
        return id;
    }

    /** Called only AFTER an independently committed event apply(). */
    public void advanceHistoryCursor(long eventId) {
        if(eventId<1)throw new IllegalArgumentException("Invalid historical event ID");
        int changed=db.update(
            "UPDATE attendance_history_backfill_cursor " +
            "SET last_event_id=?,updated_at=NOW() " +
            "WHERE singleton=TRUE AND last_event_id<?",eventId,eventId);
        if(changed!=1 && historyCursor()<eventId)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance history cursor failed to advance");
    }

    /**
     * Payroll reference evidence contains ONLY source status and provenance:
     * no Payroll rates, no PII and no externally writable fields. A DELETE
     * tombstone remains visible so it cannot be mistaken for a paid lesson.
     */
    public record PayrollEvidence(String teachingScheduleId,String eventKind,
                                  String status,long sourceEventId,String sourceHash) {}

    public record PayrollHistoryEvent(long eventId,String teachingScheduleId,
                                     String eventKind,String status,String sourceHash) {}

    /**
     * History source for Payroll's separate PostgreSQL inbox, including
     * acknowledged events and DELETE tombstones. Never gives Payroll actor
     * identity, full JSON payload, CS metadata or financial amounts.
     */
    public List<PayrollHistoryEvent> payrollHistory(long after,int limit) {
        if(after<0 || limit<1 || limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Payroll event cursor");
        return db.query(
            "SELECT event_id,teaching_schedule_id,event_kind," +
            "CASE WHEN event_kind='UPSERT' THEN source_payload->>'status' ELSE NULL END," +
            "payload_hash FROM attendance_teacher_event_history " +
            "WHERE event_id>? ORDER BY event_id LIMIT ?",
            (rs,i)->new PayrollHistoryEvent(rs.getLong(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5)),after,limit);
    }

    public List<PayrollEvidence> payrollEvidence(List<String> scheduleIds) {
        if(scheduleIds==null || scheduleIds.isEmpty() || scheduleIds.size()>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Attendance evidence batch size");
        String placeholders=String.join(",",java.util.Collections.nCopies(
            scheduleIds.size(),"?"));
        return db.query(
            "SELECT teaching_schedule_id,event_kind,status,source_event_id,source_hash " +
            "FROM attendance_teacher_ledger WHERE teaching_schedule_id IN (" +
            placeholders + ") ORDER BY teaching_schedule_id",
            (rs,i)->new PayrollEvidence(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getString(5)),
            scheduleIds.toArray());
    }

    public long ledgerRowCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_ledger",Long.class);
        if(count==null || count<0)throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Attendance normalized ledger unavailable");
        return count;
    }

    public long historicalEventCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_event_history",Long.class);
        if(count==null || count<0)throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Attendance historical events unavailable");
        return count;
    }

    /** Receipt/history mismatches imply a torn import or missing history. */
    public long historicalReceiptDivergence() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_import_receipts r " +
            "FULL OUTER JOIN attendance_teacher_event_history h ON r.event_id=h.event_id " +
            "WHERE r.event_id IS NULL OR h.event_id IS NULL OR " +
            "r.teaching_schedule_id IS DISTINCT FROM h.teaching_schedule_id OR " +
            "r.payload_hash IS DISTINCT FROM h.payload_hash",
            Long.class);
        if(count==null || count<0)throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Attendance historical parity unavailable");
        return count;
    }

    /**
     * Full payload/version parity across independently stored projection
     * and normalized ledger, including DELETE tombstones.
     */
    public boolean matchesLedgerManifest(AttendanceSourceClient.ManifestRow expected) {
        record Stored(long eventId,String kind,String status,String id,
                      String recorder,String digest,String payload) {}
        List<Stored> current=db.query(
            "SELECT source_event_id,event_kind,status,attendance_id," +
            "recorded_by,source_hash,source_payload::text " +
            "FROM attendance_teacher_ledger WHERE teaching_schedule_id=?",
            (rs,i)->new Stored(rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),
            expected.teachingScheduleId());
        if(current.size()!=1)return false;
        Stored row=current.get(0);
        try {
            JsonNode payload=mapper.readTree(expected.payloadJson());
            JsonNode local=mapper.readTree(row.payload());
            if(payload==null || !payload.isObject() || !payload.equals(local))
                return false;
            String canonical=expected.payloadJson();
            String digest=sha256(canonical);
            boolean upsert="UPSERT".equals(expected.eventKind());
            return row.eventId()==expected.eventId() &&
                expected.eventKind().equals(row.kind()) &&
                digest.equals(row.digest()) &&
                (!upsert || (payload.path("attendance_id").asText().equals(row.id()) &&
                    payload.path("status").asText().equals(row.status()) &&
                    payload.path("recorded_by").asText().equals(row.recorder()))) &&
                (upsert || (row.id()==null && row.status()==null && row.recorder()==null));
        } catch(Exception ex) {
            return false;
        }
    }

    public boolean matchesHistoricalEvent(AttendanceSourceClient.Event event) {
        record Stored(String scheduleId,String kind,String digest,String payload) {}
        List<Stored> rows=db.query(
            "SELECT teaching_schedule_id,event_kind,payload_hash,source_payload::text " +
            "FROM attendance_teacher_event_history WHERE event_id=?",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4)),event.eventId());
        if(rows.size()!=1)return false;
        Stored row=rows.get(0);
        try {
            return event.teachingScheduleId().equals(row.scheduleId()) &&
                event.eventKind().equals(row.kind()) &&
                event.payloadHash().equals(row.digest()) &&
                mapper.readTree(event.payloadJson()).equals(mapper.readTree(row.payload()));
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
        boolean alreadyImported=!old.isEmpty();
        if(alreadyImported && (old.size()!=1 || !sha.equals(old.get(0))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Source event ID was reused with different Attendance data");

        // The new normalized ledger and immutable historical event are
        // written in the SAME independent Attendance transaction. Backfill
        // must hydrate these even if the old import receipt already exists.
        db.update(
            "INSERT INTO attendance_teacher_event_history " +
            "(event_id,teaching_schedule_id,event_kind,payload_hash,source_payload) " +
            "VALUES(?,?,?,?,CAST(? AS JSONB)) ON CONFLICT(event_id) DO NOTHING",
            event.eventId(),event.teachingScheduleId(),event.eventKind(),
            sha,event.payloadJson());
        db.update(
            "INSERT INTO attendance_teacher_ledger " +
            "(teaching_schedule_id,source_event_id,event_kind,attendance_id," +
            "status,recorded_by,source_hash,source_payload) VALUES(" +
            "?,?,?,CASE WHEN ?='UPSERT' THEN ? ELSE NULL END," +
            "CASE WHEN ?='UPSERT' THEN ? ELSE NULL END," +
            "CASE WHEN ?='UPSERT' THEN ? ELSE NULL END,?,CAST(? AS JSONB)) " +
            "ON CONFLICT(teaching_schedule_id) DO UPDATE SET " +
            "source_event_id=EXCLUDED.source_event_id," +
            "event_kind=EXCLUDED.event_kind,attendance_id=EXCLUDED.attendance_id," +
            "status=EXCLUDED.status,recorded_by=EXCLUDED.recorded_by," +
            "source_hash=EXCLUDED.source_hash,source_payload=EXCLUDED.source_payload," +
            "imported_at=NOW() WHERE " +
            "attendance_teacher_ledger.source_event_id<EXCLUDED.source_event_id",
            event.teachingScheduleId(),event.eventId(),event.eventKind(),
            event.eventKind(),json.path("attendance_id").asText(),
            event.eventKind(),json.path("status").asText(),
            event.eventKind(),json.path("recorded_by").asText(),
            sha,event.payloadJson());

        if(alreadyImported)return;

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

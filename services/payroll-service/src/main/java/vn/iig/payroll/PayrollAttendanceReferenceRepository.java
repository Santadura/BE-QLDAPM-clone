package vn.iig.payroll;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollAttendanceEvidenceClient.HistoryEvent;
import vn.iig.payroll.PayrollAttendanceEvidenceClient.HistoryPage;

/**
 * One Payroll-DB transaction per immutable reference page. The history
 * cursor advances ONLY together with the source evidence and latest-state
 * projection, never before. Out-of-order events cannot overwrite a newer
 * teacher attendance reference; DELETE tombstones remain permanently.
 */
@Repository
public class PayrollAttendanceReferenceRepository {
    private final JdbcTemplate db;
    public PayrollAttendanceReferenceRepository(JdbcTemplate db) { this.db=db; }

    public long cursor() {
        Long value=db.queryForObject(
            "SELECT last_event_id FROM payroll_attendance_reference_cursor " +
            "WHERE singleton=TRUE",Long.class);
        if(value==null || value<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll Attendance history cursor unavailable");
        return value;
    }

    public long eventCount() {
        Long n=db.queryForObject(
            "SELECT COUNT(*) FROM payroll_attendance_reference_events",Long.class);
        if(n==null || n<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll Attendance event count unavailable");
        return n;
    }

    public record Reference(String teachingScheduleId,long eventId,
                            String eventKind,String status,String sourceHash) {}

    public List<Reference> latest(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100)
            throw new IllegalArgumentException("Payroll reference lookup must be bounded");
        String placeholders=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        return db.query(
            "SELECT teaching_schedule_id,event_id,event_kind,status,source_hash " +
            "FROM payroll_attendance_reference_latest " +
            "WHERE teaching_schedule_id IN ("+placeholders+") " +
            "ORDER BY teaching_schedule_id",
            (rs,i)->new Reference(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getString(4),rs.getString(5)),ids.toArray());
    }

    /** One atomic page. Recheck page even on duplicate replay. */
    @Transactional
    public void apply(HistoryPage page) {
        if(page==null || page.events()==null || page.events().size()>100)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Invalid Payroll Attendance source page");
        Long cursor=db.queryForObject(
            "SELECT last_event_id FROM payroll_attendance_reference_cursor " +
            "WHERE singleton=TRUE FOR UPDATE",Long.class);
        if(cursor==null || cursor<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll Attendance source cursor unavailable");

        long last=cursor;
        for(HistoryEvent event:page.events()) {
            if(event==null || event.eventId()<=last ||
                    event.eventId()>page.sourceRevision() ||
                    event.teachingScheduleId()==null ||
                    !event.teachingScheduleId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                    !java.util.Set.of("UPSERT","DELETE").contains(event.eventKind()) ||
                    event.sourceHash()==null ||
                    !event.sourceHash().matches("[0-9a-f]{64}") ||
                    ("UPSERT".equals(event.eventKind()) ?
                        !java.util.Set.of("PRESENT","ABSENT","LATE").contains(event.status()) :
                        event.status()!=null))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Attendance history page includes stale or invalid revision");

            db.update(
                "INSERT INTO payroll_attendance_reference_events " +
                "(event_id,teaching_schedule_id,event_kind,status,source_hash) " +
                "VALUES(?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING",
                event.eventId(),event.teachingScheduleId(),event.eventKind(),
                event.status(),event.sourceHash());
            db.update(
                "INSERT INTO payroll_attendance_reference_latest " +
                "(teaching_schedule_id,event_id,event_kind,status,source_hash) " +
                "VALUES(?,?,?,?,?) ON CONFLICT(teaching_schedule_id) DO UPDATE SET " +
                "event_id=EXCLUDED.event_id,event_kind=EXCLUDED.event_kind," +
                "status=EXCLUDED.status,source_hash=EXCLUDED.source_hash," +
                "ingested_at=NOW() WHERE " +
                "payroll_attendance_reference_latest.event_id<EXCLUDED.event_id",
                event.teachingScheduleId(),event.eventId(),event.eventKind(),
                event.status(),event.sourceHash());
            last=event.eventId();
        }
        if(last>cursor)
            db.update(
                "UPDATE payroll_attendance_reference_cursor " +
                "SET last_event_id=?,updated_at=NOW() " +
                "WHERE singleton=TRUE AND last_event_id=?",
                last,cursor);
    }
}

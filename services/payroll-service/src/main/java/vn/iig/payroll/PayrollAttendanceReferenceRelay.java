package vn.iig.payroll;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Separate immutable Attendance->Payroll event replication.
 * Every page and its cursor commits in Payroll's own PostgreSQL before the
 * next page; the Core-origin event history remains intact in Attendance.
 * This importer never calculates salaries and cannot mutate Payroll ledgers.
 */
@Component
public class PayrollAttendanceReferenceRelay {
    private static final Logger log=LoggerFactory.getLogger(PayrollAttendanceReferenceRelay.class);
    private final PayrollAttendanceEvidenceClient attendance;
    private final PayrollAttendanceReferenceRepository reference;
    private final boolean enabled;

    public PayrollAttendanceReferenceRelay(PayrollAttendanceEvidenceClient attendance,
            PayrollAttendanceReferenceRepository reference,
            @Value("${iig.attendance.reference-relay-enabled:false}") boolean enabled) {
        this.attendance=attendance;this.reference=reference;this.enabled=enabled;
    }

    @Scheduled(initialDelayString="${iig.attendance.initial-delay-ms:10000}",
               fixedDelayString="${iig.attendance.poll-ms:6000}")
    public void tick() {
        if(!enabled)return;
        try {
            poll(5);
        } catch(Exception ex) {
            // A failure is never treated as successful replay. Keep the
            // previous committed cursor and retry from its position.
            log.warn("Payroll teacher evidence history import deferred: {}",ex.toString());
        }
    }

    /** No false readiness when history is missing or source is moving. */
    public synchronized boolean poll(int maxPages) {
        if(!enabled)return false;
        if(maxPages<1 || maxPages>250)
            throw new IllegalArgumentException("Invalid bounded Payroll replay");
        long cursor=reference.cursor();
        PayrollAttendanceEvidenceClient.HistoryPage first=null;
        for(int pageIndex=0;pageIndex<maxPages;pageIndex++) {
            var next=attendance.history(cursor,100);
            if(next==null || next.events()==null || next.events().size()>100)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Incomplete independent Attendance event history");
            if(first==null)first=next;
            else if(first.sourceRevision()!=next.sourceRevision() ||
                    first.sourceEventCount()!=next.sourceEventCount() ||
                    first.attendanceGeneration()!=next.attendanceGeneration() ||
                    first.schedulingGeneration()!=next.schedulingGeneration())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Attendance source changed between historical replay pages");
            List<PayrollAttendanceEvidenceClient.HistoryEvent> events=next.events();
            for(var event:events) {
                if(event==null || event.eventId()<=cursor)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Attendance replay cursor must increase");
                cursor=event.eventId();
            }
            reference.apply(next);
            if(events.size()<100) {
                var verified=attendance.readiness();
                if(verified.sourceRevision()!=first.sourceRevision() ||
                        verified.sourceEventCount()!=first.sourceEventCount() ||
                        verified.attendanceGeneration()!=first.attendanceGeneration() ||
                        verified.schedulingGeneration()!=first.schedulingGeneration() ||
                        reference.cursor()<verified.sourceRevision() ||
                        reference.eventCount()!=verified.sourceEventCount() ||
                        reference.divergentLatestRows()!=0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Payroll teacher evidence history not yet fully reconciled");
                return true;
            }
        }
        return false;
    }

    public synchronized void requireCaughtUp() {
        if(!enabled)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll Attendance evidence replication is disabled");
        if(!poll(250))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll Attendance event history catch-up exceeds bounded capacity");
    }
}

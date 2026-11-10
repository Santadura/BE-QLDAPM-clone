package vn.iig.attendance;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in Core->Attendance transactional outbox replication.
 * A source event is acknowledged only AFTER the Attendance local write and
 * immutable receipt commit, so lost responses are safely replayed.
 * This does not change the current public PUT attendance writer (Core).
 */
@Component
public class AttendanceEventRelay {
    private static final Logger log=LoggerFactory.getLogger(AttendanceEventRelay.class);
    private final AttendanceSourceClient core;
    private final AttendanceEventProjectionRepository projection;
    private final boolean enabled;

    public AttendanceEventRelay(AttendanceSourceClient core,
            AttendanceEventProjectionRepository projection,
            @Value("${iig.attendance.source-relay-enabled:false}") boolean enabled) {
        this.core=core;this.projection=projection;this.enabled=enabled;
    }

    @Scheduled(initialDelayString="${iig.attendance.initial-delay-ms:8000}",
               fixedDelayString="${iig.attendance.poll-ms:5000}")
    public void tick() {
        if(!enabled) return;
        try {
            drain();
        } catch(Exception ex) {
            // Retain pending events in Core: no successful acknowledgement is
            // inferred from a partial batch or a lost HTTP response.
            log.warn("Attendance source relay deferred; Core events remain pending: {}",
                ex.toString());
        }
    }

    public int drain() {
        if(!enabled) return 0;
        int applied=0;
        for(int round=0;round<25;round++) {
            List<AttendanceSourceClient.Event> events=core.pending(100);
            if(events==null || events.size()>100)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Attendance source batch");
            if(events.isEmpty()) return applied;
            for(var event:events) {
                projection.apply(event); // separate committed Attendance transaction
                core.acknowledge(event);
                applied++;
            }
        }
        // A full last batch DOES NOT prove the queue is exhausted. Continue
        // next poll, leaving source events pending instead of a false success.
        return applied;
    }
}

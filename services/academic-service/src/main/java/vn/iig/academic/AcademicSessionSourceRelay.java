package vn.iig.academic;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core->Academic source delivery for eventual independent timetable ownership.
 * An event is acknowledged only AFTER the separate Academic transaction.
 * A failed response is not a successful acknowledgement. Bounded history
 * backfill also imports events previously acknowledged by a former process.
 */
@Component
public class AcademicSessionSourceRelay {
    private static final Logger log=LoggerFactory.getLogger(AcademicSessionSourceRelay.class);
    private final AcademicSessionSourceClient source;
    private final AcademicSessionSourceRepository target;
    private final boolean enabled;
    public AcademicSessionSourceRelay(AcademicSessionSourceClient source,
            AcademicSessionSourceRepository target,
            @Value("${iig.academic.session-relay-enabled:false}") boolean enabled) {
        this.source=source;this.target=target;this.enabled=enabled;
    }

    @Scheduled(initialDelayString="${iig.academic.session-initial-ms:10000}",
               fixedDelayString="${iig.academic.session-poll-ms:5000}")
    public void tick() {
        if(!enabled)return;
        try {
            drain();
            backfill(10);
        } catch(Exception ex) {
            log.warn("Academic source replication deferred, Core retains events: {}",
                ex.toString());
        }
    }
    public synchronized int drain() {
        return drain(30);
    }

    /**
     * Bounded synchronous catch-up on an authorized Academic GET. A burst
     * of writes should not leave clients dependent on the scheduler delay.
     */
    public synchronized int drain(int maxPages) {
        if(!enabled)return 0;
        if(maxPages<1 || maxPages>30)
            throw new IllegalArgumentException("Academic event drain must be bounded");
        int count=0;
        for(int page=0;page<maxPages;page++) {
            List<AcademicSessionSourceClient.Event> batch=source.pending(100);
            if(batch==null || batch.size()>100)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Core Academic pending batch");
            if(batch.isEmpty())return count;
            for(var event:batch) {
                target.apply(event,false); // committed in Academic DB
                source.acknowledge(event); // only THEN confirm to Core
                count++;
            }
        }
        return count; // do not claim an exhausted feed after bounded drain
    }
    public synchronized boolean backfill(int pages) {
        if(!enabled)return false;
        if(pages<1 || pages>250)
            throw new IllegalArgumentException("Academic historical replay must be bounded");
        long cursor=target.historyCursor();
        for(int page=0;page<pages;page++) {
            List<AcademicSessionSourceClient.Event> batch=source.history(cursor,100);
            if(batch==null || batch.size()>100)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Core Academic historical batch");
            if(batch.isEmpty())return true;
            for(var event:batch) {
                if(event==null || event.eventId()<=cursor)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Non-monotonic Academic source history");
                target.apply(event,true); // cursor + receipt atomically committed
                cursor=event.eventId();
            }
            if(batch.size()<100)return true;
        }
        return false;
    }
}

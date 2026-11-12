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
 * Bounded historical replay. There is no shared database access, Core data
 * deletion, or public read/write switch. The cursor and shadow rows commit
 * inside the same Academic PostgreSQL transaction.
 */
@Component
public class AcademicRosterSourceRelay {
    private static final Logger log=LoggerFactory.getLogger(AcademicRosterSourceRelay.class);
    private final AcademicRosterSourceClient core;
    private final AcademicRosterSourceRepository academic;
    private final boolean enabled;

    public AcademicRosterSourceRelay(AcademicRosterSourceClient core,
            AcademicRosterSourceRepository academic,
            @Value("${iig.academic.roster-relay-enabled:false}") boolean enabled) {
        this.core=core;this.academic=academic;this.enabled=enabled;
    }

    @Scheduled(initialDelayString="${iig.academic.roster-initial-ms:12000}",
               fixedDelayString="${iig.academic.roster-poll-ms:5000}")
    public void tick() {
        if(!enabled)return;
        try {
            replay(10);
        } catch(Exception ex) {
            log.warn("Academic student roster import deferred; Core history remains durable: {}",
                ex.toString());
        }
    }

    public synchronized int replay(int pages) {
        if(!enabled)return 0;
        if(pages<1 || pages>100)
            throw new IllegalArgumentException("Academic roster replay budget must be 1..100");
        long cursor=academic.cursor();
        int imported=0;
        for(int i=0;i<pages;i++) {
            List<AcademicRosterSourceClient.Event> batch=core.history(cursor,100);
            if(batch==null || batch.size()>100)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core student roster history page malformed");
            if(batch.isEmpty())return imported;
            for(var event:batch) {
                if(event==null || event.eventId()<=cursor)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Core student roster history is not strictly monotonic");
                academic.apply(event); // commits receipt, latest row and cursor together
                cursor=event.eventId();
                imported++;
            }
            if(batch.size()<100)return imported;
        }
        return imported; // bounded; never imply that the feed was exhausted
    }
}

package vn.iig.academic;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exhaustive read-only parity proof, not permission to cut over writes.
 * Source integrity, every immutable event, typed shadows and Core watermark
 * must all agree. A concurrent mutation forces fail-closed/retry.
 */
@Service
public class AcademicRosterReadiness {
    private static final int PAGE_SIZE=100;
    private static final int MAX_PAGES=10000;
    private final AcademicRosterSourceClient core;
    private final AcademicRosterSourceRepository local;
    public record Evidence(long sourceEvents,long lastEventId,
        long students,long memberships,long distinctEntities) {}

    public AcademicRosterReadiness(AcademicRosterSourceClient core,
            AcademicRosterSourceRepository local) {
        this.core=core;this.local=local;
    }

    private static ResponseStatusException incomplete(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Academic student/roster is not reconciled: "+reason);
    }

    public Evidence verify() {
        var before=core.summary();
        var sourceIntegrity=core.integrity();
        if(before==null || sourceIntegrity==null ||
            before.sourceEvents()<0 || before.lastEventId()<0 ||
            before.sourceEvents()<before.distinctEntities() ||
            sourceIntegrity.divergentRows()!=0 ||
            sourceIntegrity.newestStudentRows()!=sourceIntegrity.studentRows() ||
            sourceIntegrity.newestMembershipRows()!=sourceIntegrity.membershipRows() ||
            before.studentRows()!=sourceIntegrity.studentRows() ||
            before.membershipRows()!=sourceIntegrity.membershipRows())
            throw incomplete("Core history or live roster rows diverged");

        if(local.cursor()!=before.lastEventId() ||
            local.receiptCount()!=before.sourceEvents() ||
            local.studentCount()!=before.studentRows() ||
            local.membershipCount()!=before.membershipRows() ||
            local.latestDivergence()!=0)
            throw incomplete("Academic immutable history or typed projections differ");

        long cursor=0,count=0;
        boolean exhausted=false;
        for(int page=0;page<MAX_PAGES;page++) {
            List<AcademicRosterSourceClient.Event> history=core.history(cursor,PAGE_SIZE);
            if(history==null || history.size()>PAGE_SIZE)
                throw incomplete("Invalid Core roster history page");
            if(history.isEmpty()) {exhausted=true;break;}
            for(var e:history) {
                if(e==null || e.eventId()<=cursor ||
                    e.eventId()>before.lastEventId() || !local.matchesEvent(e))
                    throw incomplete("Missing or divergent immutable roster event");
                cursor=e.eventId();
                if(++count>before.sourceEvents())
                    throw incomplete("Core source events grew during verification");
            }
            if(history.size()<PAGE_SIZE) {exhausted=true;break;}
        }
        if(!exhausted || count!=before.sourceEvents() || cursor!=before.lastEventId())
            throw incomplete("Student/roster history scan incomplete");
        var afterIntegrity=core.integrity();
        var after=core.summary();
        if(!before.equals(after) || !sourceIntegrity.equals(afterIntegrity) ||
            local.cursor()!=after.lastEventId() ||
            local.receiptCount()!=after.sourceEvents() ||
            local.latestDivergence()!=0)
            throw incomplete("Core roster changed while Academic proof was running");
        return new Evidence(count,cursor,before.studentRows(),
            before.membershipRows(),before.distinctEntities());
    }
}

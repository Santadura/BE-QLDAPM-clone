package vn.iig.academic;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only cutover proof: EVERY historical source event, not merely the
 * latest projection or an empty pending feed, must match Academic PostgreSQL.
 * The proof never grants or changes writer ownership.
 */
@Service
public class AcademicSessionCutoverReadiness {
    private static final int PAGE_SIZE=100;
    private static final int MAX_PAGES=10000;
    private final AcademicSessionSourceClient core;
    private final AcademicSessionSourceRepository local;
    private final AcademicCandidateClaimClient claims;
    private final AcademicSessionCandidateRepository candidates;
    public record Evidence(long sourceEvents,long lastEventId,long sourceRows,
        long distinctSessions,long academicGeneration,long attendanceGeneration,
        long schedulingGeneration) {}

    public AcademicSessionCutoverReadiness(AcademicSessionSourceClient core,
            AcademicSessionSourceRepository local,
            AcademicCandidateClaimClient claims,
            AcademicSessionCandidateRepository candidates) {
        this.core=core;this.local=local;this.claims=claims;
        this.candidates=candidates;
    }
    private static ResponseStatusException incomplete(String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Academic timetable cutover not ready: "+why);
    }
    private static void requireFrozen(AcademicSessionSourceClient.WriterFence fence) {
        if(fence==null || !"QUIESCED".equals(fence.academicMode()) ||
                !"QUIESCED".equals(fence.attendanceMode()) ||
                !"QUIESCED".equals(fence.schedulingMode()) ||
                fence.academicGeneration()<1 || fence.attendanceGeneration()<1 ||
                fence.schedulingGeneration()<1)
            throw incomplete("all three Core writers must be QUIESCED");
    }

    private static void requireNoOpenClaims(AcademicCandidateClaimClient.Summary summary) {
        if(summary==null)
            throw incomplete("Core candidate claim ledger unavailable");
        if(summary.openClaims()!=0)
            throw incomplete("unreleased Core Academic candidate claim exists");
    }

    /** Strict production gate: NOT ONE staged, unapplied rehearsal may remain. */
    public Evidence verify() {
        return check(false);
    }

    /** Frozen staging admission permits rehearsal but never grants live writes. */
    public Evidence verifyStaging() {
        return check(true);
    }

    private Evidence check(boolean stagingAdmission) {
        var before=core.writerFence();
        requireFrozen(before);
        // A complete history snapshot is NOT cutover-ready if a concurrent
        // candidate still owns a Core claim. No automatic release is allowed.
        var beforeClaims=claims.summary();
        requireNoOpenClaims(beforeClaims);
        long beforeRehearsals=candidates.unpromotedRehearsalCount();
        if(!stagingAdmission && beforeRehearsals!=0)
            throw incomplete("unpromoted REHEARSAL_ONLY timetable proposal exists");
        var source=core.integrity();
        if(source==null || source.sourceRows()<0 ||
                source.trackedLiveRows()!=source.sourceRows() ||
                source.eventCount()<0 || source.pendingEvents()!=0 ||
                source.maxEventId()<0 || source.distinctSessions()<0 ||
                source.divergentRows()!=0)
            throw incomplete("Core source history is missing or diverges from live lessons");

        if(local.historyCursor()!=source.maxEventId() ||
                local.receiptCount()!=source.eventCount() ||
                local.latestCount()!=source.distinctSessions() ||
                local.latestDivergence()!=0 || local.typedDivergence()!=0)
            throw incomplete("Academic history cursor or projection parity mismatch");

        long cursor=0, scanned=0;
        boolean exhausted=false;
        for(int page=0;page<MAX_PAGES;page++) {
            List<AcademicSessionSourceClient.Event> batch=core.history(cursor,PAGE_SIZE);
            if(batch==null || batch.size()>PAGE_SIZE)
                throw incomplete("Invalid Core history page");
            if(batch.isEmpty()) {
                exhausted=true;break;
            }
            for(var event:batch) {
                if(event==null || event.eventId()<=cursor ||
                        event.eventId()>source.maxEventId() ||
                        !local.matchesEvent(event))
                    throw incomplete("Immutable event/receipt mismatch at "+cursor);
                cursor=event.eventId();
                scanned++;
                if(scanned>source.eventCount())
                    throw incomplete("Source history exceeds committed event count");
            }
            if(batch.size()<PAGE_SIZE) {
                exhausted=true;break;
            }
        }
        if(!exhausted || scanned!=source.eventCount() ||
                cursor!=source.maxEventId())
            throw incomplete("Historical scan incomplete");

        // Detect CORE -> QUIESCED -> CORE -> QUIESCED (ABA) using generations.
        var afterIntegrity=core.integrity();
        var afterFence=core.writerFence();
        var afterClaims=claims.summary();
        requireNoOpenClaims(afterClaims);
        long afterRehearsals=candidates.unpromotedRehearsalCount();
        if(!source.equals(afterIntegrity) || !before.equals(afterFence) ||
                !beforeClaims.equals(afterClaims) ||
                beforeRehearsals!=afterRehearsals ||
                (!stagingAdmission && afterRehearsals!=0))
            throw incomplete("Core source, claim ledger or writer generation changed during proof");

        return new Evidence(scanned,cursor,source.sourceRows(),
            source.distinctSessions(),afterFence.academicGeneration(),
            afterFence.attendanceGeneration(),afterFence.schedulingGeneration());
    }
}

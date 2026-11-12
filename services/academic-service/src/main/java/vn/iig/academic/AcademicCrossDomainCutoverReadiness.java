package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only precondition for a FUTURE multi-domain Academic ownership cutover.
 * A session/attendance/scheduling write could invalidate a roster snapshot,
 * so ALL FOUR Core writer fences must be frozen and generation-stable.
 *
 * Both histories must be complete and both immutable Core claim ledgers must
 * have zero OPEN claims before AND after the independent PostgreSQL scans.
 * This method grants NO write permission and NEVER changes a Core writer.
 */
@Service
public class AcademicCrossDomainCutoverReadiness {
    public record Evidence(long rosterGeneration,long sessionGeneration,
        long attendanceGeneration,long schedulingGeneration,
        long rosterEvents,long sessionEvents,long rosterLastEventId,
        long sessionLastEventId,long rosterClaimAuditEvents,
        long sessionClaimAuditEvents) {}

    private final AcademicRosterSourceClient rosterCore;
    private final AcademicSessionSourceClient sessionCore;
    private final AcademicRosterCutoverReadiness roster;
    private final AcademicSessionCutoverReadiness sessions;
    private final AcademicRosterClaimClient rosterClaims;
    private final AcademicCandidateClaimClient sessionClaims;

    public AcademicCrossDomainCutoverReadiness(
            AcademicRosterSourceClient rosterCore,
            AcademicSessionSourceClient sessionCore,
            AcademicRosterCutoverReadiness roster,
            AcademicSessionCutoverReadiness sessions,
            AcademicRosterClaimClient rosterClaims,
            AcademicCandidateClaimClient sessionClaims) {
        this.rosterCore=rosterCore;this.sessionCore=sessionCore;
        this.roster=roster;this.sessions=sessions;
        this.rosterClaims=rosterClaims;this.sessionClaims=sessionClaims;
    }
    private static ResponseStatusException blocked(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Cross-domain Academic cutover not ready: "+reason);
    }
    private static void requireFrozen(
            AcademicRosterSourceClient.WriterFence rosterFence,
            AcademicSessionSourceClient.WriterFence sessionFence) {
        if(rosterFence==null || sessionFence==null ||
            !"QUIESCED".equals(rosterFence.mode()) ||
            !"QUIESCED".equals(sessionFence.academicMode()) ||
            !"QUIESCED".equals(sessionFence.attendanceMode()) ||
            !"QUIESCED".equals(sessionFence.schedulingMode()) ||
            rosterFence.generation()<2 ||
            sessionFence.academicGeneration()<1 ||
            sessionFence.attendanceGeneration()<1 ||
            sessionFence.schedulingGeneration()<1)
            throw blocked("roster, academic sessions, attendance and scheduling must all be QUIESCED");
    }
    private static void requireNoClaims(AcademicRosterClaimClient.Summary r,
            AcademicCandidateClaimClient.Summary s) {
        if(r==null || s==null || r.openClaims()!=0 || s.openClaims()!=0)
            throw blocked("one or more Core claim ledgers contain an unreleased claim");
    }

    public Evidence verify() {
        var rf0=rosterCore.writerFence();
        var sf0=sessionCore.writerFence();
        requireFrozen(rf0,sf0);
        var rc0=rosterClaims.summary();
        var sc0=sessionClaims.summary();
        requireNoClaims(rc0,sc0);

        // Each proof exhaustively verifies historical events, live rows,
        // independently persisted receipts and typed Academic projections.
        var rp=roster.verify();
        var sp=sessions.verify();

        var rf1=rosterCore.writerFence();
        var sf1=sessionCore.writerFence();
        var rc1=rosterClaims.summary();
        var sc1=sessionClaims.summary();
        requireFrozen(rf1,sf1);
        requireNoClaims(rc1,sc1);
        if(!rf0.equals(rf1) || !sf0.equals(sf1) ||
            !rc0.equals(rc1) || !sc0.equals(sc1) ||
            rf0.generation()!=rp.writerGeneration() ||
            sf0.academicGeneration()!=sp.academicGeneration() ||
            sf0.attendanceGeneration()!=sp.attendanceGeneration() ||
            sf0.schedulingGeneration()!=sp.schedulingGeneration())
            throw blocked("writer ABA, source ownership or candidate claim ledger changed during proof");

        return new Evidence(rf1.generation(),sf1.academicGeneration(),
            sf1.attendanceGeneration(),sf1.schedulingGeneration(),
            rp.sourceEvents(),sp.sourceEvents(),rp.lastEventId(),
            sp.lastEventId(),rc1.auditEvents(),sc1.auditEvents());
    }
}

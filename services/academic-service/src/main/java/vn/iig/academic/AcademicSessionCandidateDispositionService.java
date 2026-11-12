package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * An explicitly audited disposal of a NON-AUTHORITATIVE Academic timetable
 * rehearsal. This is NOT a writer transfer, event publication or rollback of
 * a live domain mutation. Nothing here changes Core's writer controls.
 */
@Service
public class AcademicSessionCandidateDispositionService {
    public record Request(String requestKey,String commandId) {}
    private final AcademicCoreClient identity;
    private final AcademicSessionSourceClient source;
    private final AcademicCandidateClaimClient claims;
    private final AcademicCandidateReconciliation reconciliation;
    private final AcademicSessionCandidateDispositionRepository dispositions;

    public AcademicSessionCandidateDispositionService(
            AcademicCoreClient identity,AcademicSessionSourceClient source,
            AcademicCandidateClaimClient claims,
            AcademicCandidateReconciliation reconciliation,
            AcademicSessionCandidateDispositionRepository dispositions) {
        this.identity=identity;this.source=source;this.claims=claims;
        this.reconciliation=reconciliation;this.dispositions=dispositions;
    }

    private static void requireCoreOwned(
            AcademicSessionSourceClient.WriterFence fence,
            AcademicCandidateClaimClient.Summary ledger) {
        if(fence==null || ledger==null ||
            !"CORE".equals(fence.academicMode()) ||
            !"CORE".equals(fence.attendanceMode()) ||
            !"CORE".equals(fence.schedulingMode()) ||
            fence.academicGeneration()<1 ||
            fence.attendanceGeneration()<1 ||
            fence.schedulingGeneration()<1 || ledger.openClaims()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "All three Core writer controls and zero unresolved claims required");
    }

    private static void requireExactRelease(
            AcademicCandidateReconciliation.Diagnosis diagnosis,
            String actor,Request request) {
        if(diagnosis==null || !"CONSISTENT_RELEASED".equals(diagnosis.state()) ||
            !"RELEASED".equals(diagnosis.coreState()) ||
            !actor.equals(diagnosis.actor()) ||
            !request.requestKey().equals(diagnosis.requestKey()) ||
            !request.commandId().equalsIgnoreCase(
                diagnosis.localCommandId()==null?"":diagnosis.localCommandId()) ||
            !diagnosis.hasAtomicAcademicEvidence() ||
            !diagnosis.hasLocalHandoffReceipt() ||
            diagnosis.requiresManualReview())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exact released Core claim and immutable Academic session proof required");
    }

    @Transactional
    public AcademicSessionCandidateDispositionRepository.Receipt discard(
            String bearer,Request request) {
        if(request==null || request.requestKey()==null ||
            !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            request.commandId()==null ||
            !request.commandId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact session rehearsal request key and command UUID required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || actor.username()==null ||
            !actor.username().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current Core Admin required to discard session rehearsal");
        var fenceBefore=source.writerFence();
        var claimsBefore=claims.summary();
        requireCoreOwned(fenceBefore,claimsBefore);
        var before=dispositions.summary();

        // A local receipt alone is not proof the Core release still exists.
        // Consult the real Core service and re-verify its exact immutable
        // command/outbox identity across the independent database boundary.
        requireExactRelease(reconciliation.inspect(bearer,
            actor.username(),request.requestKey()),actor.username(),request);
        var receipt=dispositions.discard(actor.username(),
            request.requestKey(),request.commandId(),actor.username());
        requireExactRelease(reconciliation.inspect(bearer,
            actor.username(),request.requestKey()),actor.username(),request);

        var after=dispositions.summary();
        var fenceAfter=source.writerFence();
        var claimsAfter=claims.summary();
        requireCoreOwned(fenceAfter,claimsAfter);
        // Catch writer-generation ABA, new claims and local competing writes.
        // Discard may add at most one immutable row; replay adds none.
        if(!fenceBefore.equals(fenceAfter) ||
            !claimsBefore.equals(claimsAfter) ||
            after.commands()!=before.commands() ||
            after.pendingHandoffs()!=before.pendingHandoffs() ||
            after.disposed()<before.disposed() ||
            after.disposed()>before.disposed()+1 ||
            after.unresolved()!=before.unresolved()-
                (after.disposed()-before.disposed()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core writer fence, claim or Academic disposition changed during closure");
        return receipt;
    }
}

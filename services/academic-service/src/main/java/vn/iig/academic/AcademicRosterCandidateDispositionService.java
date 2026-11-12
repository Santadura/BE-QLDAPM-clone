package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Core evidence before/after immutable Academic transaction.
 * REHEARSAL_DISCARDED is NOT promotion to an authoritative write.
 * Core writer must be CORE, not merely a temporarily frozen snapshot.
 */
@Service
public class AcademicRosterCandidateDispositionService {
    public record Request(String requestKey,String commandId) {}
    public record Receipt(String requestKey,String commandId,String disposition) {}
    private final AcademicCoreClient identity;
    private final AcademicRosterSourceClient source;
    private final AcademicRosterClaimClient claims;
    private final AcademicRosterCandidateDispositionRepository dispositions;
    private final AcademicRosterCandidateReconciliation reconciliation;

    public AcademicRosterCandidateDispositionService(AcademicCoreClient identity,
            AcademicRosterSourceClient source,AcademicRosterClaimClient claims,
            AcademicRosterCandidateDispositionRepository dispositions,
            AcademicRosterCandidateReconciliation reconciliation) {
        this.identity=identity;this.source=source;
        this.claims=claims;this.dispositions=dispositions;
        this.reconciliation=reconciliation;
    }

    private static void requireExactRehearsal(
            AcademicRosterCandidateReconciliation.Diagnosis proof,
            String actor,Request request,boolean afterDiscard) {
        String state=afterDiscard?"REHEARSAL_DISCARDED":null;
        if(proof==null || !actor.equals(proof.actor()) ||
            !request.requestKey().equals(proof.requestKey()) ||
            !request.commandId().equalsIgnoreCase(
                proof.localCommandId()==null?"":proof.localCommandId()) ||
            !"RELEASED".equals(proof.coreState()) ||
            !proof.hasCompleteAcademicEvidence() ||
            !proof.hasLocalHandoffReceipt() ||
            (afterDiscard
                ? !state.equals(proof.state()) || !proof.hasDisposition()
                : !("RELEASED_REHEARSAL_UNAPPLIED".equals(proof.state()) ||
                    "REHEARSAL_DISCARDED".equals(proof.state()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exact released Core claim and immutable Academic rehearsal proof required");
    }

    @Transactional
    public Receipt discard(String bearer,Request request) {
        if(request==null || request.requestKey()==null ||
            !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            request.commandId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic rehearsal command identity required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()) ||
            actor.username()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Core Admin required to close Academic rehearsal");
        var first=source.writerFence();
        var firstClaims=claims.summary();
        if(first==null || !"CORE".equals(first.mode()) ||
            firstClaims==null || firstClaims.openClaims()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core must own roster writes and have zero unresolved claims");
        // The global zero-OPEN count is insufficient: a restored Core
        // snapshot could lose this exact RELEASED claim while Academic keeps
        // a local handoff receipt. Compare immutable identities and outbox
        // evidence across BOTH databases before accepting a disposition.
        requireExactRehearsal(reconciliation.inspect(
            bearer,actor.username(),request.requestKey()),
            actor.username(),request,false);
        dispositions.discard(actor.username(),request.requestKey(),
            request.commandId(),actor.username());
        // Our local transaction must see its new disposition; an idempotent
        // replay must likewise retain the same exact cross-DB evidence.
        requireExactRehearsal(reconciliation.inspect(
            bearer,actor.username(),request.requestKey()),
            actor.username(),request,true);
        var last=source.writerFence();
        var lastClaims=claims.summary();
        if(!first.equals(last) || !firstClaims.equals(lastClaims) ||
            !"CORE".equals(last.mode()) || lastClaims.openClaims()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core writer generation or claim ledger changed during disposition");
        return new Receipt(request.requestKey(),request.commandId(),
            "REHEARSAL_DISCARDED");
    }
}

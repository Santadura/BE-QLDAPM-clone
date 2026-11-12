package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only cross-PostgreSQL claim triage. The two sides are fetched through
 * their own service boundaries; no cross-database connection or transaction.
 * A diagnostic is not an authorization to release Core or cut over writers.
 */
@Service
public class AcademicCandidateReconciliation {
    private final AcademicCoreClient identity;
    private final AcademicCandidateClaimClient core;
    private final AcademicSessionCandidateRepository local;

    public record Diagnosis(String state,String actor,String requestKey,
        String coreState,String localCommandId,boolean hasAtomicAcademicEvidence,
        boolean hasLocalHandoffReceipt,boolean requiresManualReview) {}

    public AcademicCandidateReconciliation(AcademicCoreClient identity,
            AcademicCandidateClaimClient core,
            AcademicSessionCandidateRepository local) {
        this.identity=identity;this.core=core;this.local=local;
    }

    public Diagnosis inspect(String bearer,String actor,String requestKey) {
        // Enforce CURRENT Core auth before even looking up the claim; JWT
        // signature by itself is not sufficient for disabled/revoked users.
        var principal=identity.currentPrincipal(bearer);
        if(principal==null || !"ROLE_ADMIN".equals(principal.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Admin required to inspect Academic candidate claims");
        if(actor==null ||
            !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            requestKey==null ||
            !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic claim actor/key required");
        var remote=core.inspect(actor,requestKey);
        var claim=remote.claim();
        var found=local.inspectCandidate(actor,requestKey);

        if(found==null)
            return diagnosis("CORE_ONLY_ORPHAN",actor,requestKey,
                remote.state(),null,false,false,true);

        var proof=found.evidence();
        boolean atomic=found.slotMatches() && found.outboxMatches() &&
            proof!=null && proof.commandId()!=null &&
            proof.outboxHash()!=null &&
            proof.outboxHash().matches("[0-9a-f]{64}");
        boolean exact=atomic &&
            actor.equals(proof.actor()) && requestKey.equals(proof.key()) &&
            claim.requestHash().equals(proof.digest()) &&
            claim.classId().equals(proof.classId()) &&
            claim.sessionId().equals(proof.sessionId()) &&
            claim.sourceEventId()==proof.eventId() &&
            claim.sourceRevision()==proof.revision() &&
            claim.academicGeneration()==proof.academicGeneration() &&
            claim.attendanceGeneration()==proof.attendanceGeneration() &&
            claim.schedulingGeneration()==proof.schedulingGeneration();

        if(!exact || (found.handoffPresent() && !found.handoffMatches()))
            return diagnosis("EVIDENCE_DIVERGED",actor,requestKey,
                remote.state(),proof.commandId(),atomic,
                found.handoffPresent(),true);

        if("OPEN".equals(remote.state())) {
            if(found.handoffPresent())
                return diagnosis("CORE_OPEN_WITH_LOCAL_RELEASE_RECEIPT",
                    actor,requestKey,remote.state(),proof.commandId(),
                    true,true,true);
            return diagnosis("PENDING_CORE_HANDOFF",actor,requestKey,
                remote.state(),proof.commandId(),true,false,false);
        }

        if("RELEASED".equals(remote.state()) &&
            proof.commandId().equalsIgnoreCase(remote.candidateCommandId()) &&
            proof.outboxHash().equals(remote.outboxHash())) {
            if(found.handoffPresent())
                return diagnosis("CONSISTENT_RELEASED",actor,requestKey,
                    remote.state(),proof.commandId(),true,true,false);
            return diagnosis("LOCAL_ACK_PENDING",actor,requestKey,
                remote.state(),proof.commandId(),true,false,false);
        }
        return diagnosis("EVIDENCE_DIVERGED",actor,requestKey,
            remote.state(),proof.commandId(),true,
            found.handoffPresent(),true);
    }

    private static Diagnosis diagnosis(String state,String actor,
            String key,String coreState,String commandId,
            boolean atomic,boolean handoff,boolean manual) {
        return new Diagnosis(state,actor,key,coreState,commandId,
            atomic,handoff,manual);
    }
}

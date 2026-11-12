package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private, read-only Core/Academic forensic comparison. Every source is read
 * across its own HTTP/SQL boundary. Diagnosis is NEVER an authorization to
 * release Core claims, change writer controls or mark a proposal applied.
 */
@Service
public class AcademicRosterCandidateReconciliation {
    public record Diagnosis(String state,String actor,String requestKey,
        String coreState,String localCommandId,boolean hasCompleteAcademicEvidence,
        boolean hasLocalHandoffReceipt,boolean hasDisposition,
        boolean requiresManualReview) {}

    private final AcademicCoreClient identity;
    private final AcademicRosterClaimClient core;
    private final AcademicRosterCandidateRepository local;

    public AcademicRosterCandidateReconciliation(AcademicCoreClient identity,
            AcademicRosterClaimClient core,AcademicRosterCandidateRepository local) {
        this.identity=identity;this.core=core;this.local=local;
    }

    public Diagnosis inspect(String bearer,String actor,String requestKey) {
        var principal=identity.currentPrincipal(bearer);
        if(principal==null || !"ROLE_ADMIN".equals(principal.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Core Admin required to inspect student roster claims");
        if(actor==null ||
            !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            requestKey==null ||
            !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Core roster claim actor and request key required");

        var remote=core.inspect(actor,requestKey);
        var claim=remote.claim();
        var found=local.inspectCandidate(actor,requestKey);

        if(found==null) {
            return report("OPEN".equals(remote.state())
                ? "CORE_ONLY_ORPHAN" : "RELEASED_WITHOUT_ACADEMIC_EVIDENCE",
                actor,requestKey,remote.state(),null,false,false,false,true);
        }
        var proof=found.evidence();
        boolean verified=proof!=null && found.hasOutbox() &&
            actor.equals(proof.actor()) && requestKey.equals(proof.key()) &&
            claim.requestHash().equals(proof.digest()) &&
            claim.entityType().equals(proof.entityType()) &&
            claim.entityId().equals(proof.entityId()) &&
            claim.sourceEventId()==proof.eventId() &&
            claim.sourceRevision()==proof.revision() &&
            claim.writerGeneration()==proof.writerGeneration();

        if(!verified ||
            (found.hasHandoffReceipt() && !found.handoffMatches()) ||
            (found.hasDisposition() && !found.dispositionMatches()) ||
            (found.hasDisposition() && !found.hasHandoffReceipt()))
            return report("EVIDENCE_DIVERGED",actor,requestKey,
                remote.state(),found.commandId(),false,
                found.hasHandoffReceipt(),found.hasDisposition(),true);

        if("OPEN".equals(remote.state())) {
            if(found.hasHandoffReceipt() || found.hasDisposition())
                return report("CORE_OPEN_WITH_LOCAL_RELEASE_RECEIPT",
                    actor,requestKey,remote.state(),proof.commandId(),true,
                    found.hasHandoffReceipt(),found.hasDisposition(),true);
            return report("PENDING_CORE_HANDOFF",actor,requestKey,
                remote.state(),proof.commandId(),true,false,false,false);
        }

        if(!"RELEASED".equals(remote.state()) ||
            !proof.commandId().equalsIgnoreCase(remote.candidateCommandId()) ||
            !proof.outboxHash().equals(remote.outboxHash()))
            return report("EVIDENCE_DIVERGED",actor,requestKey,
                remote.state(),proof.commandId(),true,
                found.hasHandoffReceipt(),found.hasDisposition(),true);

        if(!found.hasHandoffReceipt())
            return report("LOCAL_ACK_PENDING",actor,requestKey,
                remote.state(),proof.commandId(),true,false,false,false);
        if(found.hasDisposition())
            return report("REHEARSAL_DISCARDED",actor,requestKey,
                remote.state(),proof.commandId(),true,true,true,false);
        return report("RELEASED_REHEARSAL_UNAPPLIED",actor,requestKey,
            remote.state(),proof.commandId(),true,true,false,true);
    }

    private static Diagnosis report(String state,String actor,String key,
            String coreState,String commandId,boolean evidence,
            boolean handoff,boolean disposition,boolean manual) {
        return new Diagnosis(state,actor,key,coreState,commandId,
            evidence,handoff,disposition,manual);
    }
}

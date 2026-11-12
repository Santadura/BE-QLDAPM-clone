package vn.iig.academic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded at-least-once Core handback after independent Academic commit.
 *
 * A crash before Academic command/outbox commit has NO local evidence and
 * is NEVER eligible for release. Such a Core-only OPEN claim intentionally
 * blocks writer handback and requires manual investigation.
 *
 * A crash after Core release but before local receipt is safe to retry:
 * Core release verifies exact command UUID and SHA-256 and is idempotent.
 */
@Component
@ConditionalOnProperty(name="iig.academic.roster-handoff-recovery-enabled",
    havingValue="true")
public class AcademicRosterCandidateHandoffRecovery {
    private static final Logger log=
        LoggerFactory.getLogger(AcademicRosterCandidateHandoffRecovery.class);
    private final AcademicRosterCandidateRepository candidates;
    private final AcademicRosterClaimClient core;
    public AcademicRosterCandidateHandoffRecovery(
            AcademicRosterCandidateRepository candidates,
            AcademicRosterClaimClient core) {
        this.candidates=candidates;this.core=core;
    }

    @Scheduled(initialDelay=15000,fixedDelay=7000)
    public void reconcile() {
        try {
            for(var candidate:candidates.pendingHandoffs(25)) {
                try {recover(candidate);}
                catch(Exception ex) {
                    log.warn("Academic roster Core handback pending for command {}: {}",
                        candidate.commandId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Academic roster claim recovery scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public void recover(AcademicRosterCandidateRepository.Evidence item) {
        if(item==null || item.commandId()==null ||
            item.outboxHash()==null ||
            !item.outboxHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(
                "Exact committed Academic roster outbox evidence required");
        var proof=candidates.committedEvidence(
            item.actor(),item.key(),item.commandId());
        if(!proof.equals(item))
            throw new IllegalStateException(
                "Academic roster candidate evidence changed since scan");
        var claim=new AcademicRosterClaimClient.Claim(
            proof.actor(),proof.key(),proof.digest(),
            proof.entityType(),proof.entityId(),proof.eventId(),
            proof.revision(),proof.writerGeneration());
        core.release(new AcademicRosterClaimClient.Release(
            claim,proof.commandId(),proof.outboxHash()));
        candidates.recordHandoff(proof);
    }
}

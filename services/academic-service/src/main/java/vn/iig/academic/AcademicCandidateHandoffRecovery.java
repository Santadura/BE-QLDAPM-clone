package vn.iig.academic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reconcile the ambiguous boundary AFTER independent Academic commit but
 * BEFORE the durable Core claim release acknowledgement. Never invent a
 * claim release from absence of local data. Core-only orphan stays OPEN.
 *
 * The remote Core release is actor/key/hash/command/outbox EXACT and
 * idempotent, so a crash between release and local receipt is replayable.
 */
@Service
@ConditionalOnProperty(name="iig.academic.candidate-handoff-recovery-enabled",
    havingValue="true")
public class AcademicCandidateHandoffRecovery {
    private static final Logger log=
        LoggerFactory.getLogger(AcademicCandidateHandoffRecovery.class);
    private final AcademicSessionCandidateRepository repo;
    private final AcademicCandidateClaimClient core;
    public AcademicCandidateHandoffRecovery(AcademicSessionCandidateRepository repo,
            AcademicCandidateClaimClient core) {
        this.repo=repo;this.core=core;
    }

    @Scheduled(initialDelay=15000,fixedDelay=7000)
    public void reconcile() {
        try {
            for(var item:repo.pendingHandoffs(25)) {
                try { recover(item); }
                catch(Exception ex) {
                    log.warn("Academic Core handback pending for command {}: {}",
                        item.commandId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Academic Core claim handback scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public void recover(AcademicSessionCandidateRepository.Evidence item) {
        if(item==null || item.commandId()==null ||
            item.outboxHash()==null ||
            !item.outboxHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(
                "A complete immutable Academic candidate is required");
        // Require all three independent rows to agree before releasing Core.
        var full=repo.committedEvidence(item.actor(),item.key(),item.commandId());
        if(!full.equals(item))
            throw new IllegalStateException(
                "Academic candidate evidence diverged during recovery scan");
        var claim=new AcademicCandidateClaimClient.Claim(
            full.actor(),full.key(),full.digest(),full.classId(),
            full.sessionId(),full.eventId(),full.revision(),
            full.academicGeneration(),full.attendanceGeneration(),
            full.schedulingGeneration());
        core.release(new AcademicCandidateClaimClient.Release(
            claim,full.commandId(),full.outboxHash()));
        repo.recordHandoff(full);
    }
}

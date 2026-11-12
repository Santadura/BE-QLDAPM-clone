package vn.iig.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * No-blind-compensation repair of an ambiguous Scheduling -> Core reference.
 *
 * Requires an explicitly enabled private feature flag, QUIESCED Core writer,
 * exact event/revision/hash and typed Core Teacher assignment proof from
 * AssignmentReferenceRecoveryService. In particular an absent receipt can
 * NEVER mean the reference was not committed: leave COMPENSATING intact.
 */
@Component
@ConditionalOnProperty(name="iig.scheduling.receipt-recovery-enabled",
    havingValue="true")
public class AssignmentReceiptRecoveryWorker {
    private static final Logger log=
        LoggerFactory.getLogger(AssignmentReceiptRecoveryWorker.class);
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentReferenceRecoveryService recovery;

    public AssignmentReceiptRecoveryWorker(AssignmentDomainOutboxRepository outbox,
            AssignmentReferenceRecoveryService recovery) {
        this.outbox=outbox;this.recovery=recovery;
    }

    @Scheduled(initialDelayString="${iig.scheduling.receipt-recovery-initial-ms:20000}",
        fixedDelayString="${iig.scheduling.receipt-recovery-poll-ms:10000}")
    public void tick() {
        try {
            for(var event:outbox.awaitingVerifiedRecovery(25)) {
                try { recover(event); }
                catch(Exception ex) {
                    try { outbox.recordVerifiedRecoveryFailure(event,ex); }
                    catch(Exception storeFailure) {
                        log.error("Scheduling retry state not durable for {}",
                            event.eventId(),storeFailure);
                    }
                    log.warn("Core assignment receipt uncertain for {}: {}",
                        event.eventId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Scheduling receipt recovery scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public boolean recover(AssignmentDomainOutboxRepository.Event event) {
        if(event==null || event.eventId()==null)
            throw new IllegalArgumentException("Immutable Scheduling event required");
        var outcome=recovery.verifyAndRecover(event.eventId());
        if(outcome==null || !event.eventId().equals(outcome.eventId()) ||
            !outcome.coreReceiptVerified() ||
            !java.util.Set.of("COMMITTED","COMPENSATING").contains(outcome.state()))
            throw new IllegalStateException("Core reference did not prove this event");
        return "COMMITTED".equals(outcome.state());
    }
}

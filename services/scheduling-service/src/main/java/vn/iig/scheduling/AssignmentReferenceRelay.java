package vn.iig.scheduling;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Phase 8d: at-least-once delivery of independently committed Scheduling
 * events to Core's idempotent reference receiver.
 *
 * Default DISABLED. No public assignment endpoint produces events yet.
 * Enabling requires an explicit maintenance cutover: Core must be QUIESCED,
 * its reference endpoint separately enabled, all original assignments
 * reconciled, and an approved Scheduling assignment writer available.
 *
 * Core commits FIRST; the relay marks delivery and finishes the command in
 * a separate Scheduling transaction. If that second step fails, the event
 * remains pending and the same Core receipt is safely replayed.
 */
@Service
public class AssignmentReferenceRelay {
    private static final Logger log=LoggerFactory.getLogger(AssignmentReferenceRelay.class);
    private static final Set<String> ACCEPTED=Set.of(
        "APPLIED","ALREADY_APPLIED");

    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;
    private final SchedulingCoreClient core;
    private final boolean enabled;

    public AssignmentReferenceRelay(AssignmentDomainOutboxRepository outbox,
                                    AssignmentCommandLifecycleService lifecycle,
                                    SchedulingCoreClient core,
                                    @Value("${iig.scheduling.assignment-reference-relay-enabled:false}")
                                    boolean enabled) {
        this.outbox=outbox;
        this.lifecycle=lifecycle;
        this.core=core;
        this.enabled=enabled;
    }

    @Scheduled(initialDelay=15000,fixedDelay=5000)
    public void relay() {
        if(!enabled) return;
        for(var event:outbox.pending(50)) {
            try {
                var receipt=core.applyAssignmentReference(event);
                if(!event.eventId().equals(receipt.eventId()) ||
                    event.revision()!=receipt.revision() ||
                    !ACCEPTED.contains(receipt.outcome()))
                    throw new IllegalStateException("Core reference acknowledgement mismatch");
                // This method acks the event AND advances the corresponding
                // inbox to COMMITTED only when the entire batch is delivered.
                lifecycle.confirmReference(event.actorUsername(),event.operation(),
                    event.requestKey(),event.eventId(),event.payloadHash());
            } catch(Exception ex) {
                // The Core reference may already have committed before the
                // connection dropped. Do not compensate on a timeout; retry
                // the identical immutable event instead.
                try {
                    outbox.retryLater(event.eventId(),
                        ex.getClass().getSimpleName()+": reference delivery failed");
                    if(event.attemptCount()>=9) {
                        // Tenth failure: stop automatic delivery until both
                        // Core and Scheduling receipts are reconciled.
                        lifecycle.flagForReconciliation(
                            event.actorUsername(),event.operation(),event.requestKey(),
                            event.eventId());
                        log.error("Assignment reference event {} requires manual reconciliation",
                            event.eventId());
                    }
                } catch(Exception retryFailure) {
                    log.error("Cannot persist retry for assignment reference event {}",
                        event.eventId(),retryFailure);
                }
                log.warn("Assignment reference event {} remains pending: {}",
                    event.eventId(),ex.getClass().getSimpleName());
            }
        }
    }
}

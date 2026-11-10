package vn.iig.scheduling;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit, read-confirmed recovery for a previously exhausted Core delivery.
 * This does not publish new events, alter assignment data or perform a
 * blind compensation. Core's durable receipt must exactly prove the event.
 */
@Service
public class AssignmentReferenceRecoveryService {
    public record Recovery(String eventId,String state,boolean coreReceiptVerified) {}

    private final SchedulingCoreClient core;
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;

    public AssignmentReferenceRecoveryService(SchedulingCoreClient core,
            AssignmentDomainOutboxRepository outbox,
            AssignmentCommandLifecycleService lifecycle) {
        this.core=core;
        this.outbox=outbox;
        this.lifecycle=lifecycle;
    }

    public Recovery verifyAndRecover(String eventId) {
        // Never recover under concurrent, uncontrolled legacy Core writes.
        core.requireCoreWriterQuiesced();
        var event=outbox.unresolvedEvent(eventId);
        boolean staff="STAFF".equals(event.sourceType());
        boolean teacher="TEACHING".equals(event.sourceType()) &&
            "ASSIGN_TEACHER".equals(event.operation()) && event.revision()==1;
        if((!staff && !teacher) || !"UPSERT".equals(event.eventKind()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only immutable staff or version-1 teacher reference events qualify");

        var receipt=core.committedAssignmentReceipt(
            event.sourceType(),event.scheduleId(),event.eventId());
        if(!event.eventId().equals(receipt.eventId()) ||
                event.revision()!=receipt.revision() ||
                !event.payloadHash().equals(receipt.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core's durable reference receipt is not this event");

        boolean complete=lifecycle.reconcileVerified(event,receipt);
        return new Recovery(event.eventId(),
            complete?"COMMITTED":"COMPENSATING",true);
    }
}

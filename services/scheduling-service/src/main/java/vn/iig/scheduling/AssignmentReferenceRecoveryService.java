package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
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

    private static final ObjectMapper JSON=new ObjectMapper();
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

        if(teacher) {
            try {
                JsonNode p=JSON.readTree(event.payloadJson());
                if(p==null || !p.isObject() ||
                        !event.scheduleId().equals(p.path("id").asText()) ||
                        !"ASSIGNED".equals(p.path("status").asText()) ||
                        !receipt.teacherId().equals(p.path("teacherId").asText()) ||
                        !receipt.classId().equals(p.path("classId").asText()) ||
                        !receipt.date().equals(LocalDate.parse(p.path("date").asText())) ||
                        !receipt.startTime().equals(LocalTime.parse(p.path("startTime").asText())) ||
                        !receipt.endTime().equals(LocalTime.parse(p.path("endTime").asText())) ||
                        !receipt.status().equals(p.path("status").asText()) ||
                        !receipt.assignedBy().equals(p.path("assignedBy").asText()))
                    throw new IllegalArgumentException(
                        "Core TEACHING reference no longer matches immutable source");
            } catch(Exception ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core TEACHING reference differs from the Scheduling event",ex);
            }
        }

        boolean complete=lifecycle.reconcileVerified(event,receipt);
        return new Recovery(event.eventId(),
            complete?"COMMITTED":"COMPENSATING",true);
    }
}

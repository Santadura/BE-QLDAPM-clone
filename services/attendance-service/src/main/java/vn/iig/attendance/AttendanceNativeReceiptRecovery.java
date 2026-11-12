package vn.iig.attendance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crash-safe, bounded, opt-in native Attendance reference repair.
 * Only replays the ORIGINAL immutable Core reference request; never
 * reissues the user's command, increments native version, or impersonates CS.
 * Failure/revocation/missing proof leaves the outbox PENDING.
 */
@Component
@ConditionalOnProperty(name="iig.attendance.native-recovery-enabled",
    havingValue="true")
public class AttendanceNativeReceiptRecovery {
    private static final Logger log=
        LoggerFactory.getLogger(AttendanceNativeReceiptRecovery.class);
    private final AttendanceNativeLedgerRepository ledger;
    private final AttendanceNativeReferenceClient core;
    private final ObjectMapper mapper;

    public AttendanceNativeReceiptRecovery(AttendanceNativeLedgerRepository ledger,
            AttendanceNativeReferenceClient core,ObjectMapper mapper) {
        this.ledger=ledger;this.core=core;this.mapper=mapper;
    }

    @Scheduled(initialDelayString="${iig.attendance.native-initial-ms:17000}",
        fixedDelayString="${iig.attendance.native-poll-ms:9000}")
    public void tick() {
        try {
            for(var event:ledger.pending(25)) {
                try {repair(event);}
                catch(Exception ex) {
                    log.warn("Native Attendance Core reference still uncertain for {}: {}",
                        event.teachingScheduleId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Native Attendance recovery scan deferred: {}",
                ex.getClass().getSimpleName());
        }
        // This second scan covers a different crash boundary:
        // Core committed its OPEN claim but the independent Attendance JVM
        // died BEFORE writing an inbox/outbox row. No local-only query can
        // discover those orphaned commands.
        try {
            for(var claim:core.recoverable(25)) {
                try {recoverClaim(claim);}
                catch(Exception ex) {
                    log.warn("Native Core claim still unresolved for {}: {}",
                        claim.teachingScheduleId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Native Core orphan claim scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public boolean repair(AttendanceNativeLedgerRepository.Pending event) {
        if(event==null)
            throw new IllegalArgumentException("Original native event required");
        var receipt=core.reference(event);
        ObjectNode proof=mapper.createObjectNode()
            .put("eventId",receipt.eventId())
            .put("teachingScheduleId",receipt.teachingScheduleId())
            .put("version",receipt.version())
            .put("payloadHash",receipt.payloadHash());
        ledger.confirm(event,proof);
        return true;
    }
    /**
     * The Core admission is durable, generation-bound and already checked
     * against CURRENT active CS, support shift and Academic lesson facts by
     * the private Core scanner. Restage the ORIGINAL command atomically in
     * Attendance, not a newly invented CS request, and relay its exact
     * event. Re-running this method cannot create a second domain mutation.
     */
    public boolean recoverClaim(AttendanceNativeReferenceClient.Claim claim) {
        if(claim==null || !"OPEN".equals(claim.state()) ||
            !"ATTENDANCE".equals(claim.ownerMode()) ||
            claim.coreClaimId()==null || claim.writerGeneration()<2)
            throw new IllegalArgumentException("Exact active Core claim required");
        var admitted=new AttendanceNativeLedgerRepository.Admission(
            claim.actorUsername(),claim.actorEmployeeId(),claim.classId(),
            claim.teachingScheduleId(),claim.requestKey(),claim.requestHash(),
            claim.status(),claim.factsToken(),claim.writerGeneration(),
            claim.coreClaimId(),claim.previousAttendanceId(),
            claim.previousStatus(),claim.ownerMode());
        var local=ledger.stage(admitted);
        var event=ledger.event(local.eventId());
        if(event==null || !event.actorUsername().equals(claim.actorUsername()) ||
            !event.classId().equals(claim.classId()) ||
            !event.teachingScheduleId().equals(claim.teachingScheduleId()) ||
            !event.requestKey().equals(claim.requestKey()))
            throw new IllegalStateException("Recovered claim has no matching immutable event");
        return repair(event);
    }

}

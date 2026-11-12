package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transactional native Attendance command saga.
 * In the only permitted sequence:
 *   active CS -> owner+facts -> Core durable claim -> Attendance local
 *   domain+inbox+outbox commit -> Core atomic reference+receipt commit ->
 *   Attendance immutable receipt finalization.
 *
 * A timeout NEVER makes a local PENDING_REFERENCE a business success. A
 * retry presents the original immutable event ID. The client need not keep
 * its bearer token in either database.
 */
@Service
public class AttendanceNativeCommandService {
    private static final Pattern KEY=
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private final AttendanceCoreClient identity;
    private final AttendanceNativeReferenceClient core;
    private final AttendanceNativeLedgerRepository nativeLedger;

    public AttendanceNativeCommandService(AttendanceCoreClient identity,
            AttendanceNativeReferenceClient core,
            AttendanceNativeLedgerRepository nativeLedger) {
        this.identity=identity;this.core=core;this.nativeLedger=nativeLedger;
    }

    public JsonNode mark(String classId,String scheduleId,String bearer,
            String rawStatus,String key) {
        if(key==null || !KEY.matcher(key).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance requires a stable 1-128 character Idempotency-Key");
        String status=AttendanceCommandService.normalizedStatus(rawStatus);
        var actor=identity.currentPrincipal(bearer);
        if(!"ROLE_CS".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a live CS may mark native teacher attendance");
        var facts=core.facts(classId,scheduleId,bearer);
        String fingerprint=AttendanceCommandService.fingerprint(
            classId,scheduleId,status);
        var claim=core.claim(classId,scheduleId,bearer,key,fingerprint,status,
            facts.token(),actor.username());
        if(!facts.actorEmployeeId().equals(claim.actorEmployeeId()) ||
            facts.attendanceGeneration()!=claim.writerGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance claim and exact CS facts generation diverged");
        var admitted=new AttendanceNativeLedgerRepository.Admission(
            actor.username(),claim.actorEmployeeId(),classId,scheduleId,key,
            fingerprint,status,facts.token(),claim.writerGeneration(),
            claim.coreClaimId(),claim.previousAttendanceId(),
            claim.previousStatus(),claim.ownerMode());
        var local=nativeLedger.stage(admitted);
        var event=nativeLedger.event(local.eventId());
        if(event==null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Native Attendance command committed without outbox evidence");
        var proof=core.reference(event);
        // Never acknowledge business success until BOTH PostgreSQLs contain
        // durable transaction-specific confirmation.
        var receipt=new com.fasterxml.jackson.databind.ObjectMapper()
            .createObjectNode()
            .put("eventId",proof.eventId())
            .put("teachingScheduleId",proof.teachingScheduleId())
            .put("version",proof.version())
            .put("payloadHash",proof.payloadHash());
        nativeLedger.confirm(event,receipt);
        return proof.response();
    }
}

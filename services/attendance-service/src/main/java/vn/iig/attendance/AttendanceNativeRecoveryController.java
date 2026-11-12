package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE operator finality control: inspection and proof-only recovery.
 * Never compensates an Attendance event or toggles Core writer ownership.
 * A timed-out Core response cannot establish rollback; only an exact
 * immutable Core receipt is sufficient to leave OPERATOR_REVIEW.
 */
@RestController
@RequestMapping("/internal/attendance/native/recovery")
@ConditionalOnProperty(name="iig.attendance.native-recovery-enabled",havingValue="true")
public class AttendanceNativeRecoveryController {
    public record Verified(String eventId,String state) {}
    private final AttendanceCoreClient identity;
    private final AttendanceNativeLedgerRepository ledger;
    private final AttendanceNativeReceiptRecovery repair;
    private final byte[] secret;

    public AttendanceNativeRecoveryController(AttendanceCoreClient identity,
            AttendanceNativeLedgerRepository ledger,
            AttendanceNativeReceiptRecovery repair,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Attendance private recovery key required");
        this.identity=identity;this.ledger=ledger;this.repair=repair;
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }

    private void requireAdmin(String key,String bearer) {
        if(!MessageDigest.isEqual(secret,
                (key==null?"":key).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Valid private Attendance recovery key required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Admin required for native Attendance recovery");
    }

    @GetMapping("/status")
    public AttendanceNativeLedgerRepository.RecoverySummary status(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        requireAdmin(key,bearer);
        return ledger.recoverySummary();
    }

    @PostMapping("/{eventId}/verify")
    public Verified verify(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String eventId) {
        requireAdmin(key,bearer);
        if(eventId==null || !eventId.matches("[0-9a-fA-F-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact native Attendance event ID required");
        if(!ledger.requiresOperatorReview(eventId))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a quarantined, unconfirmed original event may be verified");
        var event=ledger.event(eventId);
        if(event==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Original Attendance event was not found");
        // Same original canonical event; Core idempotently proves its
        // business COMMIT and local confirm atomically resolves quarantine.
        repair.repair(event);
        return new Verified(eventId,"VERIFIED_COMMITTED");
    }
}

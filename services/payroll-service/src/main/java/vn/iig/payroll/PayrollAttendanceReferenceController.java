package vn.iig.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private operational proof of complete Payroll-owned teacher attendance
 * history. This endpoint only verifies source and advances immutable evidence
 * history; it never authorizes any public salary calculation.
 */
@RestController
@RequestMapping("/internal/payroll/attendance-reference")
public class PayrollAttendanceReferenceController {
    private final PayrollAttendanceReferenceRelay relay;
    private final PayrollAttendanceReferenceRepository reference;
    private final byte[] key;

    public record Report(boolean ready,long lastEventId,long eventCount) {}

    public PayrollAttendanceReferenceController(
            PayrollAttendanceReferenceRelay relay,
            PayrollAttendanceReferenceRepository reference,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        this.relay=relay;this.reference=reference;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/verify")
    public ResponseEntity<Report> verify(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        byte[] received=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,received))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Payroll Attendance reference key");
        relay.requireCaughtUp();
        return ResponseEntity.ok(new Report(true,reference.cursor(),reference.eventCount()));
    }
}

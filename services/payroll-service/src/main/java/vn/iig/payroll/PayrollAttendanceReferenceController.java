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
    private final PayrollAttendanceEvidenceClient attendance;
    private final byte[] key;

    public record Report(boolean ready,long lastEventId,long eventCount,
                         long attendanceGeneration,long schedulingGeneration) {}

    public PayrollAttendanceReferenceController(
            PayrollAttendanceReferenceRelay relay,
            PayrollAttendanceReferenceRepository reference,
            PayrollAttendanceEvidenceClient attendance,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        this.relay=relay;this.reference=reference;this.attendance=attendance;
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
        // An HTTP response is not a transaction across Attendance and Payroll.
        // Require TWO independently sourced, generation-stable snapshots
        // around Payroll's local cursor/count/parity queries; never infer
        // business finality from a matching count alone.
        var before=attendance.readiness();
        long cursor=reference.cursor();
        long count=reference.eventCount();
        long divergent=reference.divergentLatestRows();
        var after=attendance.readiness();
        if(before==null || after==null ||
            before.sourceRevision()<0 || before.sourceEventCount()<0 ||
            before.attendanceGeneration()<1 || before.schedulingGeneration()<1 ||
            before.sourceRevision()!=after.sourceRevision() ||
            before.sourceEventCount()!=after.sourceEventCount() ||
            before.attendanceGeneration()!=after.attendanceGeneration() ||
            before.schedulingGeneration()!=after.schedulingGeneration() ||
            cursor!=after.sourceRevision() || count!=after.sourceEventCount() ||
            divergent!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Payroll immutable Attendance reference finality changed or diverged");
        return ResponseEntity.ok(new Report(true,cursor,count,
            after.attendanceGeneration(),after.schedulingGeneration()));
    }
}

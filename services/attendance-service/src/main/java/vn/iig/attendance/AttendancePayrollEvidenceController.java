package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 8f-9: INTERNAL read-only reference evidence for Payroll.
 *
 * Before issuing a proof, all Core Attendance source events and tombstones
 * must match independent Attendance PostgreSQL. This is NOT a new Payroll
 * authority and DOES NOT permit independent Attendance writes.
 * Kept off Gateway; requires Payroll's distinct private service key.
 */
@RestController
@RequestMapping("/internal/attendance/payroll")
public class AttendancePayrollEvidenceController {
    private final AttendanceEventProjectionRepository projection;
    private final AttendanceCutoverVerifier verifier;
    private final AttendanceSourceClient source;
    private final byte[] key;

    public record Snapshot(long sourceRevision,long sourceEventCount,
                           long attendanceGeneration,long schedulingGeneration,
                           List<AttendanceEventProjectionRepository.PayrollEvidence> evidence) {}

    public AttendancePayrollEvidenceController(
            AttendanceEventProjectionRepository projection,
            AttendanceCutoverVerifier verifier,AttendanceSourceClient source,
            @Value("${iig.payroll.evidence-key}") String serviceKey) {
        if(serviceKey==null || serviceKey.isBlank())
            throw new IllegalArgumentException(
                "PAYROLL_INTERNAL_API_KEY required for Attendance evidence");
        this.projection=projection;
        this.verifier=verifier;
        this.source=source;
        this.key=serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/evidence")
    public ResponseEntity<Snapshot> evidence(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestParam String ids) {
        byte[] presented=(provided==null?"":provided).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,presented))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Payroll evidence key");
        if(ids==null || ids.isBlank() || ids.length()>5100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance evidence needs a bounded set of schedule IDs");
        List<String> wanted=Arrays.asList(ids.split(",",-1));
        if(wanted.size()>100 || wanted.size()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance evidence batch must contain 1-100 schedule IDs");
        Set<String> distinct=new HashSet<>();
        for(String id:wanted) {
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                    !distinct.add(id))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid or duplicate teacher schedule identity");
        }

        // Double-check versioned source evidence around the local read:
        // an ABA writer-mode transition or a new Core attendance event fails
        // closed. This live proof is NOT a distributed write lease.
        var fenceBefore=source.writerFence();
        var summaryBefore=source.manifestSummary();
        var parity=verifier.verify();
        if(!parity.ready())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core and Attendance source history or ledger disagree");

        List<AttendanceEventProjectionRepository.PayrollEvidence> actual=
            projection.payrollEvidence(wanted);
        var fenceAfter=source.writerFence();
        var summaryAfter=source.manifestSummary();
        if(!fenceBefore.equals(fenceAfter) ||
                !summaryBefore.equals(summaryAfter) ||
                summaryAfter.pendingEvents()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Attendance source changed during Payroll evidence verification");
        return ResponseEntity.ok(new Snapshot(summaryAfter.maxEventId(),
            summaryAfter.eventCount(),fenceAfter.attendanceGeneration(),
            fenceAfter.schedulingGeneration(),List.copyOf(actual)));
    }
}

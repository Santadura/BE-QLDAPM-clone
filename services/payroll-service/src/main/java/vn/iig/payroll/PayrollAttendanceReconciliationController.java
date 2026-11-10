package vn.iig.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal Payroll source-parity preflight. Not a public calculation route.
 * A mismatch has HTTP 409; loss of either source is 503. No payments change.
 */
@RestController
@RequestMapping("/internal/payroll/attendance-reconciliation")
public class PayrollAttendanceReconciliationController {
    private final PayrollAttendanceReconciliationService service;
    private final byte[] key;

    public PayrollAttendanceReconciliationController(
            PayrollAttendanceReconciliationService service,
            @Value("${iig.core.internal-key}") String payrollKey) {
        if(payrollKey==null || payrollKey.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        this.service=service;
        this.key=payrollKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<PayrollAttendanceReconciliationService.Report> compare(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestParam String period) {
        byte[] supplied=(provided==null?"":provided).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Payroll reconciliation service key");
        var result=service.compare(period);
        return ResponseEntity.status(result.ready()?HttpStatus.OK:HttpStatus.CONFLICT)
            .body(result);
    }
}

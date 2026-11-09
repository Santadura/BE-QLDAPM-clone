package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only callable within the Compose network with an internal service key.
 * Gateway has no /internal/** route. Never enable write cutover based only on
 * an outdated report: obtain a fresh result immediately before a drain.
 */
@RestController
public class AssignmentCutoverVerificationController {
    private final AssignmentCutoverVerificationService verifier;
    private final byte[] secret;

    public AssignmentCutoverVerificationController(
            AssignmentCutoverVerificationService verifier,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.verifier=verifier;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/scheduling/assignments/verify")
    public ResponseEntity<AssignmentCutoverVerificationService.Report> verify(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        byte[] candidate=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling service key");
        var report=verifier.verify();
        return ResponseEntity.status(report.ready()?HttpStatus.OK:HttpStatus.CONFLICT)
            .body(report);
    }
}

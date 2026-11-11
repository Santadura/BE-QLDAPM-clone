package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private read-only preflight. No public Gateway route and no writer switch. */
@RestController
@RequestMapping("/internal/academic/cutover/sessions")
public class AcademicSessionCutoverReadinessController {
    private final AcademicSessionCutoverReadiness verifier;
    private final byte[] secret;
    public AcademicSessionCutoverReadinessController(
            AcademicSessionCutoverReadiness verifier,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Private Academic service key required");
        this.verifier=verifier;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/readiness")
    public ResponseEntity<AcademicSessionCutoverReadiness.Evidence> readiness(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        byte[] candidate=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic service key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(verifier.verify());
    }
}

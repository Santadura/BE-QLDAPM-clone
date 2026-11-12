package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal read-only diagnostic, never routed through public Gateway. */
@RestController
@RequestMapping("/internal/academic/cutover/full-readiness")
public class AcademicFullCutoverReadinessController {
    private final AcademicFullCutoverReadiness verifier;
    private final byte[] secret;

    public AcademicFullCutoverReadinessController(
            AcademicFullCutoverReadiness verifier,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Private Academic cutover key required");
        this.verifier=verifier;
        secret=key.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping
    public ResponseEntity<AcademicFullCutoverReadiness.Evidence> verify(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        byte[] supplied=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic cutover key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(verifier.verify());
    }
}

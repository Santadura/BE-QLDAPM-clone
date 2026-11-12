package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verified Academic, Attendance and Scheduling read-only diagnostics.
 * All Core writers stay QUIESCED during the independent proof.
 * Live Core ADMIN identity AND private service key required.
 */
@RestController
@RequestMapping("/internal/academic/cutover/full-readiness")
public class AcademicCrossDomainCutoverReadinessController {
    private final AcademicCoreClient identity;
    private final AcademicFullCutoverReadiness readiness;
    private final byte[] key;

    public AcademicCrossDomainCutoverReadinessController(AcademicCoreClient identity,
            AcademicFullCutoverReadiness readiness,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Cross-domain cutover service key required");
        this.identity=identity;this.readiness=readiness;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping
    public ResponseEntity<AcademicFullCutoverReadiness.Evidence> inspect(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        byte[] provided=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private cross-domain cutover key required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Core Admin required for multi-domain Academic cutover");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(readiness.verify());
    }
}

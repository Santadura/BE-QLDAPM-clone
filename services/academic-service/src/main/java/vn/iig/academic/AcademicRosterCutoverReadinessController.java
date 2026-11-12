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
 * Private, non-mutating cutover prerequisite check. Never exposed by Gateway.
 * Even with a service key, a current live Core Admin identity is required.
 */
@RestController
@RequestMapping("/internal/academic/student-roster/cutover/readiness")
public class AcademicRosterCutoverReadinessController {
    private final AcademicCoreClient identity;
    private final AcademicRosterCutoverReadiness readiness;
    private final byte[] secret;

    public AcademicRosterCutoverReadinessController(AcademicCoreClient identity,
            AcademicRosterCutoverReadiness readiness,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Private roster cutover key required");
        this.identity=identity;
        this.readiness=readiness;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<AcademicRosterCutoverReadiness.Evidence> inspect(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        byte[] provided=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic roster cutover key required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Core Admin required for Academic roster cutover proof");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(readiness.verify());
    }
}

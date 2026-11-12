package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private diagnostic. This never authorizes changing a domain writer. */
@RestController
@RequestMapping("/internal/academic/student-roster/readiness")
public class AcademicRosterReadinessController {
    private final AcademicCoreClient identity;
    private final AcademicRosterReadiness readiness;
    private final byte[] key;

    public AcademicRosterReadinessController(AcademicCoreClient identity,
            AcademicRosterReadiness readiness,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Academic roster readiness key required");
        this.identity=identity;this.readiness=readiness;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<AcademicRosterReadiness.Evidence> inspect(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        var actual=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic roster readiness key required");
        var principal=identity.currentPrincipal(bearer);
        if(principal==null || !"ROLE_ADMIN".equals(principal.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live admin required for student-roster reconciliation");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(readiness.verify());
    }
}

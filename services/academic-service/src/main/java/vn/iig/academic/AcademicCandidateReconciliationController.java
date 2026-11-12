package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private, non-mutating diagnosis. Never routed by Gateway. */
@RestController
@RequestMapping("/internal/academic/cutover/sessions/candidates/reconciliation")
public class AcademicCandidateReconciliationController {
    private final AcademicCandidateReconciliation diagnosis;
    private final byte[] secret;

    public AcademicCandidateReconciliationController(
            AcademicCandidateReconciliation diagnosis,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Academic diagnosis key required");
        this.diagnosis=diagnosis;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<AcademicCandidateReconciliation.Diagnosis> inspect(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam String actor,@RequestParam String requestKey) {
        byte[] supplied=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic diagnosis service key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(diagnosis.inspect(bearer,actor,requestKey));
    }
}

package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal-only read-only forensic endpoint. Never routed by Gateway. */
@RestController
@RequestMapping("/internal/academic/student-roster/candidates/reconciliation")
public class AcademicRosterCandidateReconciliationController {
    private final AcademicRosterCandidateReconciliation diagnosis;
    private final byte[] secret;

    public AcademicRosterCandidateReconciliationController(
            AcademicRosterCandidateReconciliation diagnosis,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Academic roster diagnosis key required");
        this.diagnosis=diagnosis;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<AcademicRosterCandidateReconciliation.Diagnosis> inspect(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam String actor,@RequestParam String requestKey) {
        byte[] supplied=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic roster diagnosis service key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(diagnosis.inspect(bearer,actor,requestKey));
    }
}

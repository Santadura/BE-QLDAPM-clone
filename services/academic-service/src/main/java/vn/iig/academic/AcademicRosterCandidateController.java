package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private opt-in write rehearsal. Not routed by Gateway, not a domain writer.
 * A live Core Admin JWT PLUS separate service key are BOTH mandatory.
 */
@RestController
@RequestMapping("/internal/academic/student-roster/candidates")
@ConditionalOnProperty(name="iig.academic.roster-candidate-enabled",havingValue="true")
public class AcademicRosterCandidateController {
    private final AcademicRosterCandidateService service;
    private final byte[] key;

    public AcademicRosterCandidateController(AcademicRosterCandidateService service,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Private Academic candidate key required");
        this.service=service;this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public AcademicRosterCandidateRepository.Receipt stage(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AcademicRosterCandidateService.Request request) {
        byte[] provided=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic target/result candidate key required");
        // Stage returns ONLY after Academic's independent SQL transaction has
        // committed all three immutable facts. Remote release is a distinct
        // idempotent call; a crash here is repaired from local outbox evidence.
        var receipt=service.stage(bearer,request);
        service.acknowledge(bearer,request,receipt);
        return receipt;
    }
}

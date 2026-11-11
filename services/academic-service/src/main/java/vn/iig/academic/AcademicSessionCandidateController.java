package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE opt-in rehearsal API; deliberately not connected to Gateway,
 * AcademicWriteController, or the Core reference projection.
 */
@RestController
@RequestMapping("/internal/academic/cutover/sessions/candidates")
@ConditionalOnProperty(name="iig.academic.candidate-writer-enabled",havingValue="true")
public class AcademicSessionCandidateController {
    private final AcademicSessionCandidateService service;
    private final byte[] key;

    public AcademicSessionCandidateController(AcademicSessionCandidateService service,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Private Academic candidate key required");
        this.service=service;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping
    public AcademicSessionCandidateRepository.Receipt stage(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AcademicSessionCandidateService.Request body) {
        byte[] candidate=(provided==null?"":provided).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic operator service key required");
        // Finish the Academic PG transaction BEFORE remote handback release.
        var receipt=service.stage(bearer,body);
        service.acknowledge(bearer,body,receipt);
        return receipt;
    }
}

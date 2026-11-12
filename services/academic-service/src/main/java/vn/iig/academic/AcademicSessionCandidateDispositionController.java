package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit opt-in PRIVATE session rehearsal disposition. OFF by default,
 * never routed by the public Gateway, and never grants production writes.
 */
@RestController
@RequestMapping("/internal/academic/cutover/sessions/rehearsals/discard")
@ConditionalOnProperty(name="iig.academic.session-disposition-enabled",
    havingValue="true")
public class AcademicSessionCandidateDispositionController {
    private final AcademicSessionCandidateDispositionService service;
    private final byte[] secret;

    public AcademicSessionCandidateDispositionController(
            AcademicSessionCandidateDispositionService service,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Private session disposition key required");
        this.service=service;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<AcademicSessionCandidateDispositionRepository.Receipt> discard(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AcademicSessionCandidateDispositionService.Request request) {
        byte[] provided=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic session disposition service key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.discard(bearer,request));
    }
}

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

/** Private audited closure for non-authoritative candidate proposals. */
@RestController
@RequestMapping("/internal/academic/student-roster/rehearsals/discard")
@ConditionalOnProperty(name="iig.academic.roster-disposition-enabled",
    havingValue="true")
public class AcademicRosterCandidateDispositionController {
    private final AcademicRosterCandidateDispositionService service;
    private final byte[] key;

    public AcademicRosterCandidateDispositionController(
            AcademicRosterCandidateDispositionService service,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Private Academic disposition key required");
        this.service=service;this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<AcademicRosterCandidateDispositionService.Receipt> discard(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AcademicRosterCandidateDispositionService.Request request) {
        byte[] provided=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic rehearsal discard key required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.discard(bearer,request));
    }
}

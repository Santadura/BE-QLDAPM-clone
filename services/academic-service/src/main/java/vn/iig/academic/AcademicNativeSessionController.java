package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private, opt-in Admin-only native mutation REHEARSAL.
 * No Gateway mapping and no Core write. 202 does not mean business success.
 */
@RestController
@RequestMapping("/internal/academic/native")
@ConditionalOnProperty(name="iig.academic.native-rehearsal-enabled",havingValue="true")
public class AcademicNativeSessionController {
    public record ActivationResult(String state,long importedRows) {}
    private final AcademicNativeSessionService service;
    private final byte[] key;
    public AcademicNativeSessionController(AcademicNativeSessionService service,
            @Value("${iig.core.internal-key}") String privateKey) {
        if(privateKey==null || privateKey.isBlank())
            throw new IllegalArgumentException("Private Academic key required");
        this.service=service;
        this.key=privateKey.getBytes(StandardCharsets.UTF_8);
    }
    private void requireKey(String supplied) {
        if(!MessageDigest.isEqual(key,
            (supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic native service key required");
    }
    @PostMapping("/activate/{classId}")
    public ResponseEntity<ActivationResult> activate(
        @RequestHeader(value="X-Internal-Key",required=false) String supplied,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @PathVariable String classId) {
        requireKey(supplied);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            new ActivationResult("REHEARSAL_ONLY",service.activate(classId,bearer)));
    }
    @PutMapping("/classes/{classId}/planned-sessions")
    public ResponseEntity<JsonNode> plan(
        @RequestHeader(value="X-Internal-Key",required=false) String supplied,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
        @PathVariable String classId,@RequestBody JsonNode body) {
        requireKey(supplied);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("X-Academic-Native-State","REHEARSAL_ONLY")
            .body(service.execute("PLAN",classId,body,bearer,requestKey));
    }
    @PostMapping("/classes/{classId}/reschedule")
    public ResponseEntity<JsonNode> reschedule(
        @RequestHeader(value="X-Internal-Key",required=false) String supplied,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
        @PathVariable String classId,@RequestBody JsonNode body) {
        requireKey(supplied);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .header("X-Academic-Native-State","REHEARSAL_ONLY")
            .body(service.execute("RESCHEDULE",classId,body,bearer,requestKey));
    }
}

package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, private operator summary. The existing Admin-only
 * /{eventId}/verify endpoint is the ONLY way to resolve a quarantined
 * Scheduling reference and must still verify the immutable Core receipt.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reconciliation")
@ConditionalOnProperty(name="iig.scheduling.recovery-enabled",havingValue="true")
public class AssignmentRecoveryStatusController {
    private final SchedulingCoreClient core;
    private final AssignmentDomainOutboxRepository outbox;
    private final byte[] secret;
    public AssignmentRecoveryStatusController(SchedulingCoreClient core,
            AssignmentDomainOutboxRepository outbox,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Scheduling private recovery key required");
        this.core=core;this.outbox=outbox;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/recovery-status")
    public AssignmentDomainOutboxRepository.RecoverySummary status(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        if(!MessageDigest.isEqual(secret,
                (key==null?"":key).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Scheduling recovery key required");
        var actor=core.currentViewer(bearer);
        if(actor==null || actor.username()==null || !"ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Admin required for Scheduling recovery status");
        return outbox.recoverySummary();
    }
}

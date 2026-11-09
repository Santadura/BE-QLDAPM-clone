package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-only reconciliation of a Core-verified event. Not a Gateway route.
 * No manual "mark delivered" escape hatch: this requires the exact immutable
 * receipt from Core and only applies to COMPENSATING assignment commands.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reconciliation")
@ConditionalOnProperty(name="iig.scheduling.recovery-enabled",havingValue="true")
public class AssignmentReferenceRecoveryController {
    private final AssignmentReferenceRecoveryService service;
    private final SchedulingCoreClient core;
    private final byte[] internalKey;

    public AssignmentReferenceRecoveryController(
            AssignmentReferenceRecoveryService service,SchedulingCoreClient core,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.service=service;
        this.core=core;
        this.internalKey=key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{eventId}/verify")
    public AssignmentReferenceRecoveryService.Recovery verify(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable String eventId) {
        byte[] supplied=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(internalKey,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling reconciliation service key");
        var actor=core.currentViewer(authorization);
        if(actor==null || actor.username()==null || !"ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Active administrator required for saga reconciliation");
        return service.verifyAndRecover(eventId);
    }
}

package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private ADMIN-only, opt-in, read-only diagnostics. It cannot change a
 * Scheduling command, Core writer mode, reference receipt, or Core claim.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reconciliation/teacher")
@ConditionalOnProperty(name="iig.scheduling.recovery-enabled",havingValue="true")
public class TeacherClaimReconciliationController {
    private final TeacherClaimReconciliationService service;
    private final SchedulingCoreClient core;
    private final byte[] key;

    public TeacherClaimReconciliationController(
        TeacherClaimReconciliationService service,SchedulingCoreClient core,
        @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.service=service;this.core=core;
        this.key=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/diagnose")
    public TeacherClaimReconciliationService.Diagnosis diagnose(
        @RequestHeader(value="X-Internal-Key",required=false) String supplied,
        @RequestHeader(value="Authorization",required=false) String authorization,
        @RequestParam String actorUsername,@RequestParam String requestKey) {
        byte[] presented=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,presented))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid teacher claim diagnosis service key");
        var viewer=core.currentViewer(authorization);
        if(viewer==null || viewer.username()==null || !"ADMIN".equals(viewer.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Active administrator required for teacher claim diagnosis");
        return service.diagnose(actorUsername,requestKey);
    }
}

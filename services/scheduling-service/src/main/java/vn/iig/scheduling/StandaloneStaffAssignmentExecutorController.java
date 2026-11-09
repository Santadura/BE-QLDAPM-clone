package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-controlled migration-only endpoint. NOT routed by Gateway.
 * Does not support class support, teacher schedules, edits or cancellation.
 * When disabled (normal operation), this controller is not registered at all.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/commands/standalone")
@ConditionalOnProperty(name="iig.scheduling.assignment-executor-enabled",
                       havingValue="true")
public class StandaloneStaffAssignmentExecutorController {
    private final StandaloneStaffAssignmentExecutor executor;
    private final StandaloneStaffCancellationExecutor cancellation;
    private final SchedulingCoreClient core;
    private final byte[] sharedKey;

    public StandaloneStaffAssignmentExecutorController(
            StandaloneStaffAssignmentExecutor executor,
            StandaloneStaffCancellationExecutor cancellation,
            SchedulingCoreClient core,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.executor=executor;
        this.cancellation=cancellation;
        this.core=core;
        this.sharedKey=key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{requestKey}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StandaloneStaffAssignmentExecutor.Result execute(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable String requestKey) {
        byte[] candidate=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(sharedKey,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling service key");
        var actor=core.currentViewer(authorization);
        if(actor.employeeId()==null || actor.username()==null || !"CM".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Active CM role required to execute a standalone staff assignment");
        return executor.execute(actor.username(),actor.employeeId(),actor.role(),requestKey);
    }

    @PostMapping("/{requestKey}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StandaloneStaffAssignmentExecutor.Result cancel(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String authorization,
            @PathVariable String requestKey) {
        byte[] candidate=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(sharedKey,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling service key");
        var actor=core.currentViewer(authorization);
        if(actor.employeeId()==null || actor.username()==null || !"CM".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Active CM required to cancel standalone staff assignment");
        return cancellation.cancel(actor.username(),actor.employeeId(),actor.role(),requestKey);
    }
}

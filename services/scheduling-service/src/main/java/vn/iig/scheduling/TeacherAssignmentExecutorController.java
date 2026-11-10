package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private opt-in independent teacher command path. Never public via Gateway.
 * Requires a live TC bearer, a separate service key, a staged command and
 * explicit operator-owned writer cutover. Does not return business success
 * until the Core reference relay has confirmed the immutable outbox event.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/commands/teacher")
@ConditionalOnProperty(name="iig.scheduling.teacher-executor-enabled",
                       havingValue="true")
public class TeacherAssignmentExecutorController {
    private final TeacherAssignmentExecutor executor;
    private final SchedulingCoreClient core;
    private final byte[] secret;

    public TeacherAssignmentExecutorController(TeacherAssignmentExecutor executor,
            SchedulingCoreClient core,
            @Value("${iig.core.internal-key}") String serviceKey) {
        if(serviceKey==null || serviceKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.executor=executor;this.core=core;
        this.secret=serviceKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{requestKey}/execute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TeacherAssignmentExecutor.Result execute(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String requestKey) {
        if(!MessageDigest.isEqual(secret,
                (supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Scheduling teacher executor key");
        var actor=core.currentViewer(bearer);
        if(actor==null || actor.username()==null ||
                actor.employeeId()==null || !"TC".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live TC identity required to execute an independent teaching command");
        return executor.execute(actor.username(),actor.employeeId(),actor.role(),
            requestKey);
    }
}

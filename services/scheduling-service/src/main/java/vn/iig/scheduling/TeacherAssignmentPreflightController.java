package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private teacher assignment admission check, OFF in normal deployments.
 * Does NOT route through Gateway and does NOT write a teaching schedule.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/commands/teacher")
@ConditionalOnProperty(name="iig.scheduling.teacher-preflight-enabled",havingValue="true")
public class TeacherAssignmentPreflightController {
    private final TeacherAssignmentPreflightService service;
    private final SchedulingCoreClient core;
    private final byte[] key;

    public TeacherAssignmentPreflightController(TeacherAssignmentPreflightService service,
            SchedulingCoreClient core,@Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.service=service;this.core=core;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{requestKey}/preflight")
    public TeacherAssignmentPreflightService.Receipt prepare(
            @RequestHeader(value="X-Internal-Key",required=false) String serviceKey,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String requestKey) {
        byte[] presented=(serviceKey==null?"":serviceKey).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,presented))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling teacher preflight key");
        var actor=core.currentViewer(bearer);
        if(actor==null || !"TC".equals(actor.role()) ||
                actor.employeeId()==null || actor.username()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a live TC staff account can preflight a teacher assignment");
        return service.prepare(actor.username(),requestKey);
    }
}

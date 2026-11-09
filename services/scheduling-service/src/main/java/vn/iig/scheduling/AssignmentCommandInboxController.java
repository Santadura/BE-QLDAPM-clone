package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private staging only. No Gateway route, no assignment mutation and no public
 * command execution. A later cutover must revalidate the actor, academic
 * session version and business constraints at execution time as well.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/commands")
public class AssignmentCommandInboxController {
    private final AssignmentCommandInboxService inbox;
    private final SchedulingCoreClient core;
    private final byte[] key;

    public AssignmentCommandInboxController(AssignmentCommandInboxService inbox,
            SchedulingCoreClient core,
            @Value("${iig.core.internal-key}") String sharedKey) {
        if(sharedKey==null || sharedKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.inbox=inbox;
        this.core=core;
        this.key=sharedKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/stage")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssignmentCommandInboxService.Receipt stage(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AssignmentCommandInboxService.Request request) {
        byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Scheduling service key");
        var actor=core.currentViewer(bearer);
        if(actor.employeeId()==null || actor.username()==null || actor.role()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active staff profile required");
        return inbox.stage(actor.username(),actor.role(),request);
    }
}

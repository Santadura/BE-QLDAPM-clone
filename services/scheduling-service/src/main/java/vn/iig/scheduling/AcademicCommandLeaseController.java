package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE staged teacher lease coordinator (OFF unless migration operator
 * enables it). Every call verifies the live TC bearer in Core and the
 * exact actor-scoped STAGED assignment command, not a client-provided lease.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/academic-leases")
@ConditionalOnProperty(name="iig.scheduling.academic-lease-coordinator-enabled",havingValue="true")
public class AcademicCommandLeaseController {
    private final AcademicCommandLeaseService service;
    private final SchedulingCoreClient core;
    private final byte[] key;

    public AcademicCommandLeaseController(AcademicCommandLeaseService service,
            SchedulingCoreClient core,@Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.service=service;this.core=core;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    private String currentTc(String supplied,String bearer) {
        byte[] incoming=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,incoming))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Scheduling key");
        var actor=core.currentViewer(bearer);
        if(actor==null || !"TC".equals(actor.role()) ||
                actor.employeeId()==null || actor.username()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live TC account required for Academic teacher lease");
        return actor.username();
    }

    @PostMapping("/{requestKey}/acquire")
    public AcademicCommandLeaseService.Receipt acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String requestKey) {
        return service.acquire(currentTc(supplied,bearer),requestKey);
    }

    @PostMapping("/{requestKey}/renew")
    public AcademicCommandLeaseService.Receipt renew(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String requestKey) {
        return service.renew(currentTc(supplied,bearer),requestKey);
    }

    @DeleteMapping("/{requestKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String requestKey) {
        service.release(currentTc(supplied,bearer),requestKey);
    }
}

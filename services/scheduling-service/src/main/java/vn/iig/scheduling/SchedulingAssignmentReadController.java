package vn.iig.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GET routes moved out of Core; mutations remain in Core so atomic class/CS
 * staffing and attendance constraints continue to hold.
 */
@RestController
public class SchedulingAssignmentReadController {
    private final SchedulingAssignmentReadService service;
    private final AssignmentProjectionRepository repository;
    private final byte[] key;

    public SchedulingAssignmentReadController(SchedulingAssignmentReadService service,
            AssignmentProjectionRepository repository,
            @Value("${iig.core.internal-key}") String internalKey) {
        this.service=service;
        this.repository=repository;
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.key=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/teaching-schedules")
    public List<Map<String,Object>> teaching(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(value="weekStart",required=false) LocalDate week) {
        return service.teaching(auth,week);
    }

    @GetMapping("/api/staff-schedules")
    public List<Map<String,Object>> staff(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(value="weekStart",required=false) LocalDate week,
            @RequestParam(value="role",required=false) String role) {
        return service.staff(auth,week,role);
    }

    @GetMapping("/api/schedules/me")
    public List<Map<String,Object>> mine(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(value="weekStart",required=false) LocalDate week) {
        return service.mine(auth,week);
    }

    @PostMapping("/internal/scheduling/assignments/project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void project(@RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody AssignmentProjectionRepository.AssignmentSnapshot snapshot) {
        byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Scheduling service key");
        repository.project(snapshot);
    }
}

package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only this ingress is public through Gateway in the microservices stack.
 * Core remains the transactional Attendance source of truth until cutover.
 */
@RestController
@RequestMapping("/api/classes/{classId}/teacher-attendance")
@ConditionalOnProperty(name="iig.attendance.write-gateway-enabled",havingValue="true")
public class AttendanceWriteController {
    private final AttendanceCoreClient core;
    private final AttendanceWriteClient writer;
    public AttendanceWriteController(AttendanceCoreClient core,AttendanceWriteClient writer) {
        this.core=core;
        this.writer=writer;
    }

    @PutMapping("/{teachingScheduleId}")
    public ResponseEntity<JsonNode> mark(
            @PathVariable String classId,
            @PathVariable String teachingScheduleId,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestBody AttendanceWriteClient.MarkRequest request) {
        if(classId==null || classId.isBlank() || classId.length()>50 ||
                teachingScheduleId==null || teachingScheduleId.isBlank() ||
                teachingScheduleId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid attendance reference");
        var actor=core.currentPrincipal(bearer);
        if(!"ROLE_CS".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only active CS may mark teacher attendance");
        if(request==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance status is required");
        // Core revalidates the same JWT and enforces active support/lesson
        // under its single original local database transaction.
        JsonNode saved=writer.mark(classId,teachingScheduleId,bearer,request.status());
        return ResponseEntity.ok().body(saved);
    }
}

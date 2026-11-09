package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Preserves FE GET contract only. PUT /.../{teachingScheduleId} remains Core.
 */
@RestController
@RequestMapping("/api/classes/{classId}/teacher-attendance")
public class AttendanceReadController {
    private final AttendanceReadModelService model;
    public AttendanceReadController(AttendanceReadModelService model) {
        this.model=model;
    }

    @GetMapping
    public ResponseEntity<JsonNode> list(
            @PathVariable String classId,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        var result=model.list(classId,bearer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Attendance-Synchronized-At",
                result.synchronizedAt().toString())
            .body(result.payload());
    }
}

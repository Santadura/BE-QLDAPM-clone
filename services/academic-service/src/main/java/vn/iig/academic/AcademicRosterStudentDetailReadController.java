package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Same existing FE GET /api/students/{studentId}; writes stay with Core. */
@RestController
@RequestMapping("/api/students")
public class AcademicRosterStudentDetailReadController {
    private final AcademicRosterStudentDetailReadService service;
    public AcademicRosterStudentDetailReadController(
            AcademicRosterStudentDetailReadService service) {
        this.service=service;
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<JsonNode> detail(
            @PathVariable String studentId,
            @RequestHeader(value="Authorization",required=false) String bearer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.detail(studentId,bearer));
    }
}

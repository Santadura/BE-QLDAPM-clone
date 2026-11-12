package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Same FE student-list contract; never exposes raw private Core PII history. */
@RestController
@RequestMapping("/api/students")
public class AcademicRosterStudentReadController {
    private final AcademicRosterStudentReadService service;
    public AcademicRosterStudentReadController(AcademicRosterStudentReadService service) {
        this.service=service;
    }

    @GetMapping
    public ResponseEntity<JsonNode> list(
            @RequestHeader(value="Authorization",required=false) String bearer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(bearer));
    }
}

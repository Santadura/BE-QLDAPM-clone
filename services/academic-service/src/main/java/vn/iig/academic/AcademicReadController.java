package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Exact existing FE paths. GET only; class/session mutation paths stay in
 * Core until scheduling, academic and attendance sagas are verified.
 */
@RestController
@RequestMapping("/api")
public class AcademicReadController {
    private final AcademicReadModelService model;
    private final AcademicEventSessionReadService eventRead;
    @Value("${iig.academic.event-session-read-enabled:false}")
    private boolean eventSessionReadEnabled=false;

    public AcademicReadController(AcademicReadModelService model,
                                  AcademicEventSessionReadService eventRead) {
        this.model=model;
        this.eventRead=eventRead;
    }

    @GetMapping("/class-planning")
    public ResponseEntity<JsonNode> planning(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return response(model.planning(authorization));
    }

    @GetMapping("/classes/{classId}/planned-sessions")
    public ResponseEntity<JsonNode> sessions(
            @PathVariable String classId,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        return response(eventSessionReadEnabled
            ?eventRead.sessions(classId,authorization)
            :model.sessions(classId,authorization));
    }

    private ResponseEntity<JsonNode> response(AcademicReadModelService.Result value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Academic-Synchronized-At",value.synchronizedAt().toString())
            .body(value.payload());
    }
}

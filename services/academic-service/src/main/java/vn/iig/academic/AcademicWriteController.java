package vn.iig.academic;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Exact original FE paths and array responses through Academic Gateway. */
@RestController
@RequestMapping("/api/classes/{classId}")
public class AcademicWriteController {
    private final AcademicCommandService commands;
    public AcademicWriteController(AcademicCommandService commands) {
        this.commands=commands;
    }
    @PutMapping("/planned-sessions")
    public ResponseEntity<JsonNode> replace(@PathVariable String classId,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commands.execute("PLAN",classId,body,bearer,key));
    }
    @PostMapping("/reschedule")
    public ResponseEntity<JsonNode> reschedule(@PathVariable String classId,
        @RequestHeader(value="Authorization",required=false) String bearer,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commands.execute("RESCHEDULE",classId,body,bearer,key));
    }
}

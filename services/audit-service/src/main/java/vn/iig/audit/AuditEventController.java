package vn.iig.audit;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/audit/events")
public class AuditEventController {
    private final AuditEventRepository events;
    private final byte[] sharedKey;

    public AuditEventController(AuditEventRepository events,
            @Value("${iig.internal.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("AUDIT_INTERNAL_API_KEY must be configured");
        }
        this.events = events;
        this.sharedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    private void requireInternalKey(String supplied) {
        byte[] provided = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(sharedKey, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal service key");
        }
    }

    @PostMapping
    public ResponseEntity<Void> ingest(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                       @Valid @RequestBody AuditEvent event) {
        requireInternalKey(key);
        events.insertIfAbsent(event);
        return ResponseEntity.accepted().build();
    }

    @GetMapping
    public List<AuditEvent> list(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                  @RequestParam(defaultValue = "30") int limit) {
        requireInternalKey(key);
        return events.recent(Math.max(1, Math.min(limit, 100)));
    }
}

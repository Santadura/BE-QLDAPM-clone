package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private readiness report ONLY. No write mode is exposed by this API. */
@RestController
@RequestMapping("/internal/attendance")
public class AttendanceCutoverController {
    private final AttendanceCutoverVerifier verifier;
    private final byte[] secret;

    public AttendanceCutoverController(AttendanceCutoverVerifier verifier,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        verifier=java.util.Objects.requireNonNull(verifier);
        this.verifier=verifier;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authenticate(String key) {
        byte[] provided=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Attendance verification key");
    }

    @GetMapping("/verify")
    public ResponseEntity<AttendanceCutoverVerifier.Report> verify(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authenticate(key);
        var report=verifier.verify();
        return ResponseEntity.status(report.ready()?HttpStatus.OK:HttpStatus.CONFLICT)
            .body(report);
    }

    @GetMapping("/verify/frozen")
    public ResponseEntity<AttendanceCutoverVerifier.Report> verifyFrozen(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authenticate(key);
        var report=verifier.verifyFrozen();
        return ResponseEntity.status(report.ready()?HttpStatus.OK:HttpStatus.CONFLICT)
            .body(report);
    }
}

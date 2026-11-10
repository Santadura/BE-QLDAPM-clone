package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stable idempotent ingress: Attendance PENDING -> private Core command ->
 * Attendance COMMITTED. Lost responses retain PENDING; caller retries the
 * SAME key and Core returns the committed result without a second write.
 */
@Service
public class AttendanceCommandService {
    private static final Pattern SAFE_KEY=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<String> STATUSES=Set.of("PRESENT","ABSENT","LATE");
    private final AttendanceWriteClient writer;
    private final AttendanceCommandInbox inbox;

    public AttendanceCommandService(AttendanceWriteClient writer,AttendanceCommandInbox inbox) {
        this.writer=writer;
        this.inbox=inbox;
    }

    static String normalizedStatus(String input) {
        String status=input==null?"":input.trim().toUpperCase(Locale.ROOT);
        if(!STATUSES.contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance status must be PRESENT, ABSENT or LATE");
        return status;
    }

    static String fingerprint(String classId,String scheduleId,String status) {
        try {
            String canonical="v1\n"+classId+"\n"+scheduleId+"\n"+status;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    public JsonNode mark(String username,String classId,String scheduleId,String bearer,
            String rawStatus,String requestKey) {
        String status=normalizedStatus(rawStatus);
        if(requestKey==null)
            return writer.mark(classId,scheduleId,bearer,status,null);
        if(!SAFE_KEY.matcher(requestKey).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 1-128 safe ASCII characters");
        String hash=fingerprint(classId,scheduleId,status);
        inbox.stage(username,classId,scheduleId,requestKey,hash);
        // Core is checked even for a previously COMMITTED local request.
        // An offline Core or revoked CS must NEVER be bypassed by a cached
        // Attendance receipt in the independent database.
        JsonNode result=writer.mark(classId,scheduleId,bearer,status,requestKey);
        inbox.complete(username,classId,scheduleId,requestKey,hash,result);
        return result;
    }
}

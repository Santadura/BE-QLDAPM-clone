package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read extraction only. Teacher attendance writes and payroll evidence still
 * belong to Core. Data is never served from a stale local snapshot when
 * identity, authorization or the live source is unavailable.
 */
@Service
public class AttendanceReadModelService {
    public record Result(JsonNode payload,Instant synchronizedAt) {}
    private final AttendanceCoreClient core;
    private final AttendanceSnapshotRepository repository;
    private final ObjectMapper mapper;

    public AttendanceReadModelService(AttendanceCoreClient core,
            AttendanceSnapshotRepository repository,ObjectMapper mapper) {
        this.core=core;this.repository=repository;this.mapper=mapper;
    }

    public Result list(String classId,String bearer) {
        if(classId==null || classId.isBlank() || classId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid class identifier");
        var actor=core.currentPrincipal(bearer);
        if(!"ROLE_CS".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an active CS may view teacher attendance");
        // Core checks class_access_scopes and active CLASS_SUPPORT shifts,
        // which vary by actor even when the same class is requested.
        String source=core.teacherAttendance(classId,bearer);
        final JsonNode payload;
        try {
            payload=mapper.readTree(source);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Malformed Core Attendance response",ex);
        }
        if(payload==null || !payload.isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Core Attendance response must be an array");
        String key=hash(actor.username()+"\n"+classId);
        Instant saved=repository.save(key,actor.username(),classId,payload.toString());
        return new Result(payload,saved);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}

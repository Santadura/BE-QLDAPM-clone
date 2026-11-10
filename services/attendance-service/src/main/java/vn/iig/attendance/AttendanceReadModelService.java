package vn.iig.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Attendance owns the presence/status read source in its PostgreSQL.
 * Core still supplies the live role-scoped assignment/lesson roster and
 * verifies class access on each call. This refuses to serve a lagging
 * Attendance state or Core response after a concurrent source mutation.
 *
 * PUT teacher attendance remains Core-owned until a fenced write saga exists.
 */
@Service
public class AttendanceReadModelService {
    public record Result(JsonNode payload,Instant synchronizedAt) {}
    private final AttendanceCoreClient core;
    private final AttendanceSnapshotRepository repository;
    private final AttendanceEventProjectionRepository projection;
    private final AttendanceEventRelay relay;
    private final ObjectMapper mapper;

    public AttendanceReadModelService(AttendanceCoreClient core,
            AttendanceSnapshotRepository repository,
            AttendanceEventProjectionRepository projection,
            AttendanceEventRelay relay,ObjectMapper mapper) {
        this.core=core;
        this.repository=repository;
        this.projection=projection;
        this.relay=relay;
        this.mapper=mapper;
    }

    public Result list(String classId,String bearer) {
        if(classId==null || classId.isBlank() || classId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid class identifier");
        var actor=core.currentPrincipal(bearer);
        if(!"ROLE_CS".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an active CS may view teacher attendance");

        // Core's GET still enforces managing-CS/active-class-support rights,
        // including cancelled session visibility. Its roster is NOT a
        // substitute for the independently imported Attendance status.
        for(int attempt=0;attempt<2;attempt++) {
            JsonNode payload=parseArray(core.teacherAttendance(classId,bearer));
            relay.requireCaughtUp();
            if(verifiedAgainstAttendanceSource(payload)) {
                String key=hash(actor.username()+"\n"+classId);
                Instant saved=repository.save(key,actor.username(),classId,
                    payload.toString());
                return new Result(payload,saved);
            }
            // A Core write may have committed between our live roster GET
            // and the relay barrier. Retry both rather than serve mixed data.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Attendance read projection differs from the live Core lesson roster");
    }

    private JsonNode parseArray(String source) {
        try {
            JsonNode payload=mapper.readTree(source);
            if(payload!=null && payload.isArray()) return payload;
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Malformed Core Attendance response",ex);
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Core Attendance response must be an array");
    }

    private boolean verifiedAgainstAttendanceSource(JsonNode roster) {
        for(JsonNode row:roster) {
            if(!(row instanceof ObjectNode teacher) ||
                    teacher.path("id").asText().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Core Attendance roster has an invalid teacher shift");
            var state=projection.stateFor(teacher.path("id").asText());
            String status=state==null || "DELETE".equals(state.eventKind())
                ? null : state.status();
            String coreStatus=teacher.path("attendanceStatus").isNull() ||
                    teacher.path("attendanceStatus").isMissingNode()
                ? null : teacher.path("attendanceStatus").asText();
            if(!Objects.equals(status,coreStatus)) return false;
            // Attendance's local source is authoritative for the status.
            // Preserve the rest of the existing FE shape, including names,
            // dates and local timestamp serialization from Core.
            if(status==null) teacher.putNull("attendanceStatus");
            else teacher.put("attendanceStatus",status);
        }
        return true;
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

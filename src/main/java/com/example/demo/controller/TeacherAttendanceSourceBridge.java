package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core-to-Attendance durable private event feed. Events are emitted inside
 * the Core teacher-attendance transaction by PostgreSQL triggers. Attendance
 * acknowledges an EXACT event ID + payload SHA-256 only after its own local
 * transaction commits. An unacknowledged event is safe to replay.
 *
 * Not a public Gateway route and not a teacher-attendance mutation API.
 */
@RestController
@RequestMapping("/internal/attendance/events")
@ConditionalOnProperty(name="iig.attendance.bridge-enabled",havingValue="true")
public class TeacherAttendanceSourceBridge {
    public record Event(long eventId,String teachingScheduleId,String eventKind,
                        String payloadJson,String payloadHash) {}
    public record Ack(long eventId,String payloadHash) {}
    public record AckRequest(List<Ack> events) {}
    public record AckReceipt(int confirmed) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public TeacherAttendanceSourceBridge(JdbcTemplate db,
            @Value("${iig.attendance.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authenticate(String key) {
        byte[] supplied=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Attendance relay key");
    }

    private String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    @GetMapping
    @Transactional(readOnly=true)
    public List<Event> pending(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam(defaultValue="100") int limit) {
        authenticate(key);
        if(limit<1 || limit>200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Attendance event batch size must be between 1 and 200");
        return db.query(
            "SELECT event_id,teaching_schedule_id,event_kind,payload::text " +
            "FROM teacher_attendance_source_outbox " +
            "WHERE delivered_at IS NULL ORDER BY event_id LIMIT ?",
            (rs,i)->{
                String payload=rs.getString(4);
                return new Event(rs.getLong(1),rs.getString(2),
                    rs.getString(3),payload,sha256(payload));
            },limit);
    }

    @PostMapping("/ack")
    @Transactional
    public AckReceipt acknowledge(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestBody AckRequest request) {
        authenticate(key);
        if(request==null || request.events()==null ||
                request.events().isEmpty() || request.events().size()>200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Attendance event acknowledgement batch");
        int count=0;
        for(Ack ack:request.events()) {
            if(ack==null || ack.eventId()<1 || ack.payloadHash()==null ||
                    !ack.payloadHash().matches("[0-9a-f]{64}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid Attendance event receipt identity");
            // Lock and compare the original payload, NOT an external claim
            // that a given event ID must be considered delivered.
            List<String> stored=db.query(
                "SELECT payload::text FROM teacher_attendance_source_outbox " +
                "WHERE event_id=? FOR UPDATE",
                (rs,i)->rs.getString(1),ack.eventId());
            if(stored.size()!=1 || !sha256(stored.get(0)).equals(ack.payloadHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Attendance relay receipt does not match the Core event");
            db.update(
                "UPDATE teacher_attendance_source_outbox " +
                "SET delivered_at=COALESCE(delivered_at,NOW()) WHERE event_id=?",
                ack.eventId());
            count++;
        }
        return new AckReceipt(count);
    }
}

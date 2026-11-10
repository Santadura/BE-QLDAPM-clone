package com.example.demo.controller;

import com.example.demo.service.TeacherAttendanceCommandReceiptService;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Microservices-only command bridge. This does not transfer write ownership:
 * the existing Core service still holds all class/lesson/teacher/CS row locks
 * and emits attendance outbox events in its original transaction.
 */
@RestController
@RequestMapping("/internal/attendance/commands")
@ConditionalOnProperty(name="iig.attendance.bridge-enabled",havingValue="true")
public class TeacherAttendancePrivateCommandController {
    private final TeacherAttendanceCommandReceiptService attendance;
    private final byte[] secret;

    public TeacherAttendancePrivateCommandController(
            TeacherAttendanceCommandReceiptService attendance,
            @Value("${iig.attendance.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        this.attendance=attendance;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @PutMapping("/classes/{classId}/teacher-attendance/{teachingScheduleId}")
    public Map<String,Object> mark(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestHeader(value="Idempotency-Key",required=false) String requestKey,
            @RequestHeader(value="X-Attendance-Lesson-Token",required=false) String expectedFactsToken,
            @PathVariable String classId,
            @PathVariable String teachingScheduleId,
            @RequestBody MarkRequest request) {
        byte[] supplied=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Attendance command key");
        // The Core JWT filter validates the bearer and installs the Security
        // Context; mark() rechecks the active CS employee role and exact shift.
        // A private service key by itself must never impersonate a CS.
        if(bearer==null || !bearer.startsWith("Bearer ") ||
                bearer.substring(7).isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "A live CS bearer token is required");
        return attendance.mark(classId,teachingScheduleId,requestKey,expectedFactsToken,request);
    }
}

package com.example.demo.controller;

import com.example.demo.service.TeacherAttendanceLiveFactsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private Core read-only lesson precondition, never public through Gateway. */
@RestController
@RequestMapping("/internal/attendance/lesson-facts")
@ConditionalOnProperty(name="iig.attendance.bridge-enabled",havingValue="true")
public class TeacherAttendanceLiveFactsController {
    private final TeacherAttendanceLiveFactsService facts;
    private final byte[] secret;

    public TeacherAttendanceLiveFactsController(TeacherAttendanceLiveFactsService facts,
            @Value("${iig.attendance.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        this.facts=facts;
        secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public TeacherAttendanceLiveFactsService.Facts current(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam String classId,@RequestParam String teachingScheduleId) {
        byte[] supplied=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Attendance facts key");
        if(bearer==null || !bearer.startsWith("Bearer ") ||
                bearer.substring(7).isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Live CS bearer required for Attendance facts");
        // SchedulingAccess checks the JWT security context, live employee
        // role and exact class-support shift, never user-supplied actor IDs.
        return facts.current(classId,teachingScheduleId);
    }
}

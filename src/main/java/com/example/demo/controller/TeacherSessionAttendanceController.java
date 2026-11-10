package com.example.demo.controller;

import com.example.demo.service.TeacherSessionAttendanceService;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** CS records actual attendance; salary calculation is outside this module. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/classes/{classId}/teacher-attendance")
public class TeacherSessionAttendanceController {
    private final TeacherSessionAttendanceService attendance;

    // Legacy Compose still writes directly. Microservices Compose has exactly
    // one public write ingress: Attendance; the Core API cannot be bypassed.
    @Value("${iig.attendance.public-write-enabled:true}")
    private boolean publicWriteEnabled = true;

    @GetMapping
    public List<Map<String,Object>> list(@PathVariable String classId) {
        return attendance.list(classId);
    }

    @PutMapping("/{teachingScheduleId}")
    public Map<String,Object> mark(@PathVariable String classId,
                                   @PathVariable String teachingScheduleId,
                                   @RequestBody MarkRequest request) {
        if (!publicWriteEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Use the Attendance Service for teacher-attendance writes");
        }
        return attendance.mark(classId, teachingScheduleId, request);
    }
}

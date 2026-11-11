package com.example.demo.controller;

import com.example.demo.service.ClassSessionService;
import com.example.demo.service.ClassSessionService.PlannedSessionInput;
import com.example.demo.service.ClassSessionService.RescheduleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ClassSessionController {
    private final ClassSessionService sessions;
    @Value("${iig.academic.public-write-enabled:true}")
    private boolean publicWriteEnabled=true;

    private void requireAcademicIngress() {
        if (!publicWriteEnabled)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Use Academic Service for class-session writes");
    }

    @GetMapping("/class-planning")
    public List<Map<String,Object>> planning(Authentication authentication) {
        return sessions.listClassPlanning(authentication);
    }

    @GetMapping("/classes/{classId}/planned-sessions")
    public List<Map<String,Object>> get(@PathVariable String classId, Authentication authentication) {
        return sessions.sessions(classId, authentication);
    }

    @PostMapping("/classes/{classId}/reschedule")
    public List<Map<String,Object>> reschedule(
            @PathVariable String classId,
            @RequestBody RescheduleRequest body,
            Authentication authentication) {
        requireAcademicIngress();
        return sessions.reschedule(classId, body, authentication);
    }

    @PutMapping("/classes/{classId}/planned-sessions")
    public List<Map<String,Object>> replace(@PathVariable String classId,
            @RequestBody List<@Valid PlannedSessionInput> sessionsInput,
            Authentication authentication) {
        requireAcademicIngress();
        return sessions.replace(classId, sessionsInput, authentication);
    }
}

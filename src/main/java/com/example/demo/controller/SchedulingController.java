package com.example.demo.controller;

import com.example.demo.dto.SchedulingDtos.*;
import com.example.demo.service.AssignmentService;
import com.example.demo.service.AvailabilityService;
import com.example.demo.service.SchedulingAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SchedulingController {
    private final AvailabilityService availability;
    private final AssignmentService assignment;
    private final SchedulingAccess access;
    private final com.example.demo.service.SchedulingCommandReceiptService commands;

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam String role) {
        return assignment.employees(role);
    }

    @GetMapping("/availabilities/me")
    public List<Map<String, Object>> myAvailability(@RequestParam(required = false) LocalDate weekStart) {
        return availability.mine(weekStart);
    }

    @GetMapping("/availabilities")
    public List<Map<String, Object>> listAvailability(
        @RequestParam(required = false) String role,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) LocalDate weekStart
    ) {
        return availability.list(role, status, weekStart);
    }

    @PostMapping("/availabilities")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@Valid @RequestBody AvailabilityRequest body) {
        return availability.create(body);
    }

    @PutMapping("/availabilities/{id}")
    public Map<String, Object> editAvailability(@PathVariable String id,
                                                 @Valid @RequestBody AvailabilityRequest body) {
        return availability.update(id, body);
    }

    @DeleteMapping("/availabilities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvailability(@PathVariable String id) {
        availability.delete(id);
    }

    @PatchMapping("/availabilities/{id}/review")
    public Map<String, Object> review(@PathVariable String id, @Valid @RequestBody ReviewRequest body) {
        return availability.review(id, body);
    }

    @GetMapping("/teaching-schedules")
    public List<Map<String, Object>> teaching(@RequestParam(required = false) LocalDate weekStart) {
        return assignment.teaching(weekStart);
    }

    @PostMapping("/teaching-schedules/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> assignTeacherBatch(
            @Valid @RequestBody TeachingBatchRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("TC").username();
        return commands.execute(key,actor,"POST /api/teaching-schedules/batch",body,
            ()->assignment.assignTeacherBatch(body));
    }

    @PostMapping("/staff-schedules/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> assignSupportBatch(
            @Valid @RequestBody SupportBatchRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("CM").username();
        return commands.execute(key,actor,"POST /api/staff-schedules/batch",body,
            ()->assignment.assignSupportBatch(body));
    }

    @PostMapping("/teaching-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignTeacher(
            @Valid @RequestBody TeachingRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("TC").username();
        return commands.execute(key,actor,"POST /api/teaching-schedules",body,
            ()->assignment.assignTeacher(body));
    }

    @PutMapping("/teaching-schedules/{id}")
    public Map<String, Object> editTeacher(@PathVariable String id,
            @Valid @RequestBody TeachingRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("TC").username();
        return commands.execute(key,actor,"PUT /api/teaching-schedules/"+id,body,
            ()->assignment.editTeacher(id,body));
    }

    @DeleteMapping("/teaching-schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelTeacher(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("TC").username();
        commands.execute(key,actor,"DELETE /api/teaching-schedules/"+id,null,()->{
            assignment.cancelTeacher(id);
            return null;
        });
    }

    @GetMapping("/staff-schedules")
    public List<Map<String, Object>> staff(@RequestParam(required = false) LocalDate weekStart,
                                           @RequestParam(required = false) String role) {
        return assignment.staff(weekStart, role);
    }

    @PostMapping("/staff-schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignStaff(
            @Valid @RequestBody StaffRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("CM").username();
        return commands.execute(key,actor,"POST /api/staff-schedules",body,
            ()->assignment.assignStaff(body));
    }

    @PutMapping("/staff-schedules/{id}")
    public Map<String, Object> editStaff(@PathVariable String id,
            @Valid @RequestBody StaffRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("CM").username();
        return commands.execute(key,actor,"PUT /api/staff-schedules/"+id,body,
            ()->assignment.editStaff(id,body));
    }

    @DeleteMapping("/staff-schedules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelStaff(@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String actor=access.requireRole("CM").username();
        commands.execute(key,actor,"DELETE /api/staff-schedules/"+id,null,()->{
            assignment.cancelStaff(id);
            return null;
        });
    }

    @GetMapping("/schedules/me")
    public List<Map<String, Object>> mine(@RequestParam(required = false) LocalDate weekStart) {
        return assignment.mine(weekStart);
    }
}

package com.example.demo.controller;

import com.example.demo.service.ClassStaffingRangeService;
import com.example.demo.service.SchedulingAccess;
import com.example.demo.service.SchedulingCommandReceiptService;
import com.example.demo.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.Locale;
import com.example.demo.service.ClassStaffingRangeService.RangeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** TC manages teacher coverage; CM manages CS support, independently of payroll. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/class-planning/{classId}/staffing")
public class ClassStaffingRangeController {
    private final ClassStaffingRangeService service;
    private final SchedulingAccess access;
    private final SchedulingCommandReceiptService commands;

    @GetMapping
    public List<Map<String,Object>> coverage(@PathVariable String classId, @RequestParam String role) {
        return service.coverage(classId, role);
    }

    @PostMapping
    public Map<String,Object> assignRange(@PathVariable String classId,
            @RequestBody RangeRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key) {
        String requested=request==null || request.role()==null?"":request.role().trim().toUpperCase(Locale.ROOT);
        if (!List.of("TEACHER","CS").contains(requested))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Role must be TEACHER or CS");
        String actor=access.requireRole("TEACHER".equals(requested)?"TC":"CM").username();
        return commands.execute(key,actor,"POST /api/class-planning/"+classId+"/staffing",
            request,()->service.assignRange(classId,request));
    }
}

package com.example.demo.controller;

import com.example.demo.dto.EmployeeDtos.*;
import com.example.demo.service.EmployeeManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/employee-management")
@RequiredArgsConstructor
public class EmployeeManagementController {
    private final EmployeeManagementService employees;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) LocalDate weekStart,
            @RequestParam(required = false) String search, @RequestParam(required = false) String role,
            @RequestParam(required = false) String status, Authentication actor) {
        return employees.list(weekStart, search, role, status, actor);
    }

    @GetMapping("/salary-ranks")
    public List<Map<String, Object>> ranks(Authentication actor) { return employees.ranks(actor); }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id, Authentication actor) {
        return employees.detail(id, actor);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateEmployeeRequest request,
                                     Authentication actor) { return employees.update(id, request, actor); }

    @PatchMapping("/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable String id,
            @Valid @RequestBody ChangeEmployeeStatusRequest request, Authentication actor) {
        return employees.changeStatus(id, request.status(), actor);
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id, Authentication actor) {
        return employees.changeStatus(id, "INACTIVE", actor);
    }

    @GetMapping("/{id}/schedules")
    public List<Map<String, Object>> schedules(@PathVariable String id,
            @RequestParam(required = false) LocalDate weekStart, Authentication actor) {
        return employees.schedules(id, weekStart, actor);
    }

    @GetMapping("/{id}/salaries")
    public List<Map<String, Object>> salaries(@PathVariable String id, Authentication actor) {
        return employees.salaries(id, actor);
    }

    @GetMapping("/{id}/availabilities")
    public List<Map<String, Object>> availabilities(@PathVariable String id,
            @RequestParam(required = false) LocalDate weekStart, Authentication actor) {
        return employees.availabilities(id, weekStart, actor);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable String id, Authentication actor) {
        return employees.history(id, actor);
    }

    @GetMapping("/{id}/certificates")
    public List<Map<String, Object>> certificates(@PathVariable String id, Authentication actor) {
        return employees.certificates(id, actor);
    }

    @PostMapping("/{id}/certificates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addCertificate(@PathVariable String id, @Valid @RequestBody CertificateRequest request,
                                              Authentication actor) {
        return employees.saveCertificate(id, null, request, actor);
    }

    @PutMapping("/{id}/certificates/{certificateId}")
    public Map<String, Object> updateCertificate(@PathVariable String id, @PathVariable String certificateId,
            @Valid @RequestBody CertificateRequest request, Authentication actor) {
        return employees.saveCertificate(id, certificateId, request, actor);
    }
}

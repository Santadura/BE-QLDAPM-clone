package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.example.demo.service.EmployeeManagementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * An ACL-only bridge for an independently Payroll-owned read model.
 * Never exposes Core's salary rows or makes Core a Payroll data source.
 */
@RestController
@RequestMapping("/internal/payroll/employee-management-access")
@ConditionalOnProperty(name="iig.payroll.bridge-enabled",havingValue="true")
public class PayrollEmployeeAccessBridge {
    private final EmployeeManagementService employees;
    private final byte[] key;
    public PayrollEmployeeAccessBridge(EmployeeManagementService employees,
            @Value("${iig.payroll.internal-key}") String key) {
        this.employees=employees;
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Payroll employee ACL key required");
        this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/{id}")
    public ResponseEntity<Void> verify(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @PathVariable String id,Authentication actor) {
        if(!MessageDigest.isEqual(key,
            (supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Payroll employee scope key required");
        // Reuse exact, currently active manager/role/account policy.
        employees.detail(id,actor);
        return ResponseEntity.noContent().build();
    }
}

package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.example.demo.dto.DashboardDtos.DashboardResponse;
import com.example.demo.service.DashboardService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private migration adapter for the role-specific dashboard read model.
 * The independent Reporting service verifies the end-user JWT via /api/me
 * before invoking this service-key-protected endpoint.
 */
@RestController
@RequestMapping("/internal/reporting")
@ConditionalOnProperty(name="iig.reporting.bridge-enabled", havingValue="true")
public class DashboardInternalController {
    private final DashboardService dashboard;
    private final byte[] serviceKey;

    public DashboardInternalController(DashboardService dashboard,
            @Value("${iig.reporting.internal-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("REPORTING_INTERNAL_API_KEY is required");
        }
        this.dashboard = dashboard;
        this.serviceKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @RequestHeader(value="X-Internal-Key", required=false) String provided,
            @RequestParam String username) {
        byte[] value = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(serviceKey, value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid reporting service key");
        }
        if (username == null || username.isBlank() || username.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid username");
        }
        // Only the username is asserted; DashboardService *re-reads* the
        // current user's role/status and employee association from Core DB.
        return dashboard.getDashboard(new UsernamePasswordAuthenticationToken(
            username, null, List.of()));
    }
}

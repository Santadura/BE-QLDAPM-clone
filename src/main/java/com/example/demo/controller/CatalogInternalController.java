package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary internal migration port: bootstrap course catalog from legacy
 * data, then accept idempotent catalog projections into academic references.
 * This API is not exposed by Spring Cloud Gateway.
 */
@RestController
@RequestMapping("/internal/catalog")
@ConditionalOnProperty(name = "iig.catalog.enabled", havingValue = "true")
public class CatalogInternalController {
    public record CourseReference(String courseId, String name, String status) {}
    private final JdbcTemplate db;
    private final byte[] sharedKey;

    public CatalogInternalController(JdbcTemplate db,
            @Value("${iig.catalog.internal-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("CATALOG_INTERNAL_API_KEY must be configured");
        }
        this.db = db;
        this.sharedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String supplied) {
        byte[] provided = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(sharedKey, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid catalog service key");
        }
    }

    @GetMapping("/export")
    public List<CourseReference> exportLegacyCourses(
            @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        authorize(key);
        return db.query("SELECT course_id, name, status FROM courses ORDER BY course_id",
            (rs, n) -> new CourseReference(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    @PostMapping("/project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void project(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                        @RequestBody CourseReference event) {
        authorize(key);
        if (event == null || event.courseId() == null || event.courseId().isBlank()
                || event.courseId().length() > 50 || event.name() == null
                || event.name().isBlank() || event.name().length() > 150
                || event.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalog reference");
        }
        String status = event.status().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid course status");
        }
        // Academic's course table becomes a *reference projection*, not a
        // writable catalog. IDs and foreign-key relationships are preserved.
        db.update("INSERT INTO courses (course_id, name, status) VALUES (?, ?, ?) " +
                "ON CONFLICT (course_id) DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status",
            event.courseId(), event.name().trim(), status);
    }
}

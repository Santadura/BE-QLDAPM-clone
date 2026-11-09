package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Academic planning source for a separate bounded-context
 * PostgreSQL projection. Service-key protection; no roster or student data.
 * Class/course mutations advance the monotonic source clock transactionally.
 * This adapter does NOT authorize a client user: Academic checks the LIVE
 * Core identity and permitted TC/CM/ADMIN role before using its data.
 */
@RestController
@RequestMapping("/internal/academic/planning")
@ConditionalOnProperty(name="iig.academic.bridge-enabled",havingValue="true")
public class AcademicPlanningSnapshotBridge {
    public record Revision(long revision) {}
    public record Snapshot(long revision,List<Map<String,Object>> rows) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public AcademicPlanningSnapshotBridge(
            JdbcTemplate db,@Value("${iig.academic.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("ACADEMIC_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String provided) {
        byte[] candidate=provided==null?new byte[0]:provided.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Academic planning service key");
    }

    private long currentRevision() {
        Long value=db.queryForObject(
            "SELECT revision FROM academic_planning_revision WHERE singleton=TRUE",
            Long.class);
        if(value==null || value<1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic planning revision clock unavailable");
        return value;
    }

    @GetMapping("/version")
    @Transactional(readOnly=true)
    public Revision version(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        return new Revision(currentRevision());
    }

    @GetMapping("/snapshot")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        long revision=currentRevision();
        // MUST preserve the exact field names, ordering and status scope of
        // ClassSessionService.listClassPlanning used by the existing FE.
        List<Map<String,Object>> rows=db.queryForList(
            "SELECT c.class_id AS \"classId\", c.course_id AS \"courseId\", " +
            "c.class_code AS \"classCode\", c.name, " +
            "c.start_date AS \"startDate\", c.end_date AS \"endDate\", " +
            "c.status, cr.name AS \"courseName\" " +
            "FROM classes c JOIN courses cr ON cr.course_id=c.course_id " +
            "WHERE c.status IN ('READY','RUNNING') " +
            "ORDER BY c.start_date,c.class_code");
        return new Snapshot(revision,rows);
    }
}

package com.example.demo.controller;

import com.example.demo.service.ClassSessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional, private Academic read synchronization bridge.
 * Access is verified with exactly the same live class-level policy as the
 * public Core planned-sessions GET. A monotonic database clock is only a
 * freshness hint; it never replaces current permission checks.
 */
@RestController
@RequestMapping("/internal/academic/classes/{classId}/sessions")
@ConditionalOnProperty(name="iig.academic.bridge-enabled", havingValue="true")
public class AcademicSessionSnapshotBridge {
    public record Revision(long revision) {}
    public record Snapshot(long revision,List<Map<String,Object>> rows) {}

    private final JdbcTemplate db;
    private final ClassSessionService sessions;
    private final byte[] key;

    public AcademicSessionSnapshotBridge(JdbcTemplate db,ClassSessionService sessions,
            @Value("${iig.academic.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("Private Academic service key required");
        this.db=db;
        this.sessions=sessions;
        this.key=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String provided,String classId,Authentication auth) {
        byte[] presented=(provided==null?"":provided).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,presented))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid private Academic read key");
        if(classId==null || classId.isBlank() || classId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Academic class ID");
        // Never treat the internal service key as a substitute for the user's JWT.
        sessions.authorizeSessions(classId,auth);
    }

    private long currentRevision() {
        Long value=db.queryForObject(
            "SELECT revision FROM academic_session_revision WHERE singleton=TRUE",Long.class);
        if(value==null || value<1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session revision source unavailable");
        return value;
    }

    @GetMapping("/revision")
    @Transactional(readOnly=true)
    public Revision revision(@RequestHeader(value="X-Internal-Key",required=false) String serviceKey,
            @PathVariable String classId,Authentication auth) {
        authorize(serviceKey,classId,auth);
        return new Revision(currentRevision());
    }

    @GetMapping("/snapshot")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot snapshot(@RequestHeader(value="X-Internal-Key",required=false) String serviceKey,
            @PathVariable String classId,Authentication auth) {
        authorize(serviceKey,classId,auth);
        long at=currentRevision();
        return new Snapshot(at,sessions.sessions(classId,auth));
    }
}

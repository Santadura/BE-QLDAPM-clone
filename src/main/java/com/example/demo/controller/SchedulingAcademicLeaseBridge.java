package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core Academic lease bridge for a future Scheduling-owned assignment saga.
 * It does NOT assign any worker, grant access scope or mutate attendance.
 * Reservations can only start while Core assignment writes are QUIESCED;
 * the default normal operation remains CORE, with this endpoint unavailable
 * for mutation to prevent split-brain worker/class ownership.
 */
@RestController
@RequestMapping("/internal/scheduling/academic/leases")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingAcademicLeaseBridge {
    public record Request(String classId,String sessionId,
                          long classVersion,long sessionVersion) {}
    public record Lease(String leaseId,String classId,String sessionId,
                        long classVersion,long sessionVersion,Instant expiresAt) {}
    private record ClassState(long version,String status,LocalDate start,LocalDate end) {}
    private record SessionState(long version,String status,LocalDate date) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public SchedulingAcademicLeaseBridge(JdbcTemplate db,
            @Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String supplied) {
        byte[] input=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,input))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Academic lease service key");
    }

    @PostMapping
    @Transactional
    public Lease reserve(@RequestHeader(value="X-Internal-Key",required=false) String key,
                         @RequestBody Request request) {
        authorize(key);
        if(request==null || request.classId()==null || request.sessionId()==null ||
                request.classId().isBlank() || request.sessionId().isBlank() ||
                request.classId().length()>50 || request.sessionId().length()>50 ||
                request.classVersion()<1 || request.sessionVersion()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid class/session identifiers and expected versions required");

        // This shared row lock prevents the mode flipping back to CORE while
        // reserving. Existing Core assignment writers are already blocked.
        String mode=db.queryForObject(
            "SELECT writer_mode FROM scheduling_assignment_writer_control " +
            "WHERE singleton=TRUE FOR SHARE",String.class);
        if(!"QUIESCED".equals(mode))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core must be quiesced before reserving Academic sessions");

        List<ClassState> classes=db.query(
            "SELECT scheduling_version,status,start_date,end_date FROM classes " +
            "WHERE class_id=? FOR UPDATE",
            (rs,i)->new ClassState(rs.getLong(1),rs.getString(2),
                rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class)),
            request.classId());
        if(classes.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Academic class missing");
        ClassState cls=classes.get(0);

        List<SessionState> sessions=db.query(
            "SELECT scheduling_version,status,date FROM class_sessions " +
            "WHERE class_session_id=? AND class_id=? FOR UPDATE",
            (rs,i)->new SessionState(rs.getLong(1),rs.getString(2),
                rs.getObject(3,LocalDate.class)),
            request.sessionId(),request.classId());
        if(sessions.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Academic lesson missing");
        SessionState lesson=sessions.get(0);
        if(cls.version()!=request.classVersion() ||
                lesson.version()!=request.sessionVersion() ||
                !Set.of("READY","RUNNING").contains(cls.status()) ||
                !"PLANNED".equals(lesson.status()) ||
                lesson.date().isBefore(cls.start()) || lesson.date().isAfter(cls.end()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic class/session changed since the candidate was planned");

        // Only the same class/session can be leased once at a time. The Core
        // class row FOR UPDATE serializes competing transaction initiators.
        db.update("DELETE FROM scheduling_academic_session_leases " +
            "WHERE class_session_id=? AND expires_at<=NOW()",request.sessionId());
        Long live=db.queryForObject(
            "SELECT count(*) FROM scheduling_academic_session_leases " +
            "WHERE class_session_id=? AND expires_at>NOW()",
            Long.class,request.sessionId());
        if(live!=null && live>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session already reserved");

        String leaseId=UUID.randomUUID().toString();
        db.update("INSERT INTO scheduling_academic_session_leases " +
            "(lease_id,class_id,class_session_id,expected_class_version," +
            "expected_session_version,expires_at) " +
            "VALUES(?,?,?,?,?,NOW()+interval '5 minutes')",
            leaseId,request.classId(),request.sessionId(),
            request.classVersion(),request.sessionVersion());
        Timestamp expires=db.queryForObject(
            "SELECT expires_at FROM scheduling_academic_session_leases WHERE lease_id=?",
            Timestamp.class,leaseId);
        if(expires==null) throw new IllegalStateException("Academic lease not persisted");
        return new Lease(leaseId,request.classId(),request.sessionId(),
            request.classVersion(),request.sessionVersion(),expires.toInstant());
    }

    @PostMapping("/{id}/renew")
    @Transactional
    public Lease renew(@RequestHeader(value="X-Internal-Key",required=false) String key,
                       @PathVariable String id) {
        authorize(key);
        if(id==null || id.isBlank() || id.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid lease ID");
        // Renewal is version-checked: a changed/expired Academic session can
        // never be extended into an apparently authorized assignment.
        List<Lease> current=db.query(
            "SELECT l.class_id,l.class_session_id,l.expected_class_version," +
            "l.expected_session_version,l.expires_at " +
            "FROM scheduling_academic_session_leases l " +
            "JOIN classes c ON c.class_id=l.class_id " +
            "JOIN class_sessions s ON s.class_session_id=l.class_session_id " +
            "WHERE l.lease_id=? AND l.expires_at>NOW() " +
            "AND c.scheduling_version=l.expected_class_version " +
            "AND s.scheduling_version=l.expected_session_version " +
            "AND c.status IN ('READY','RUNNING') AND s.status='PLANNED' FOR UPDATE OF l,c,s",
            (rs,i)->new Lease(id,rs.getString(1),rs.getString(2),
                rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).toInstant()),id);
        if(current.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic lease expired or class/session version changed");
        db.update("UPDATE scheduling_academic_session_leases " +
            "SET expires_at=NOW()+interval '5 minutes' WHERE lease_id=?",id);
        Timestamp expires=db.queryForObject(
            "SELECT expires_at FROM scheduling_academic_session_leases WHERE lease_id=?",
            Timestamp.class,id);
        if(expires==null) throw new IllegalStateException("Renewed lease missing");
        Lease previous=current.get(0);
        return new Lease(id,previous.classId(),previous.sessionId(),
            previous.classVersion(),previous.sessionVersion(),expires.toInstant());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@RequestHeader(value="X-Internal-Key",required=false) String key,
                        @PathVariable String id) {
        authorize(key);
        if(id==null || id.isBlank() || id.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid lease ID");
        db.update("DELETE FROM scheduling_academic_session_leases WHERE lease_id=?",id);
    }
}

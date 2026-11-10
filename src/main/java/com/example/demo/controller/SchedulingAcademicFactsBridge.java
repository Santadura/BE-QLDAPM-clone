package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private, versioned Academic reference facts for a future Scheduling writer.
 * A caller MUST NOT use an old snapshot as final authorization for mutation;
 * later reservation + version validation will be required for ownership cutover.
 * Core continues to own the actual class sessions during phase 8b.
 */
@RestController
@RequestMapping("/internal/scheduling/academic")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingAcademicFactsBridge {
    public record SessionFacts(String classId,long classVersion,String classStatus,
            LocalDate classStart,LocalDate classEnd,String sessionId,
            long sessionVersion,String sessionStatus,LocalDate date,
            LocalTime startTime,LocalTime endTime,String classCode,
            String className) {}
    private final JdbcTemplate db;
    private final byte[] secret;

    public SchedulingAcademicFactsBridge(JdbcTemplate db,
            @Value("${iig.scheduling.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/session")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SessionFacts session(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestParam String classId,@RequestParam LocalDate date,
            @RequestParam LocalTime startTime,@RequestParam LocalTime endTime) {
        byte[] candidate=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,candidate))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Academic bridge key");
        if(classId==null || classId.isBlank() || classId.length()>50 ||
                date==null || startTime==null || endTime==null ||
                !endTime.isAfter(startTime))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid academic session query");
        List<SessionFacts> rows=db.query(
            "SELECT c.class_id,c.scheduling_version,c.status,c.start_date,c.end_date," +
            "s.class_session_id,s.scheduling_version,s.status,s.date,s.start_time,s.end_time, " +
            "c.class_code,c.name " +
            "FROM classes c JOIN class_sessions s ON s.class_id=c.class_id " +
            "WHERE c.class_id=? AND s.date=? AND s.start_time=? AND s.end_time=?",
            (rs,i)->new SessionFacts(rs.getString(1),rs.getLong(2),rs.getString(3),
                rs.getObject(4,LocalDate.class),rs.getObject(5,LocalDate.class),
                rs.getString(6),rs.getLong(7),rs.getString(8),
                rs.getObject(9,LocalDate.class),rs.getObject(10,LocalTime.class),
                rs.getObject(11,LocalTime.class),rs.getString(12),rs.getString(13)),
            classId,date,startTime,endTime);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Class planned session not found");
        return rows.get(0);
    }
}

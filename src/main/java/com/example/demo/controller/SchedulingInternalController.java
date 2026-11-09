package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only temporary private migration APIs. Scheduling owns new registrations
 * and approval status. The legacy availability table is a versioned reference
 * copy for existing Core scheduling/academic modules.
 */
@RestController
@RequestMapping("/internal/scheduling")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingInternalController {
    public record Actor(String employeeId,String username,String role,String fullName) {}
    public record Slot(String id,String employeeId,String fullName,String role,
                       LocalDate availableDate,String dayOfWeek,String shift,
                       LocalTime startTime,LocalTime endTime,String availabilityType,
                       String campus,String note,String status,Instant createdAt,
                       Instant reviewedAt,String reviewedBy,long version,boolean deleted) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public SchedulingInternalController(JdbcTemplate db,
            @Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        db.getClass(); // fail fast if JDBC not wired
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String provided) {
        byte[] bytes=provided==null?new byte[0]:provided.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,bytes))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Scheduling key");
    }

    @GetMapping("/actor")
    public Actor actor(@RequestHeader(value="X-Internal-Key",required=false) String key,
                       Authentication authentication) {
        authorize(key);
        if(authentication==null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");
        }
        List<Actor> rows=db.query(
            "SELECT e.employee_id,e.username,u.role_id,e.full_name FROM employees e " +
            "JOIN users u ON u.username=e.username WHERE e.username=? " +
            "AND e.status='ACTIVE' AND u.status='ACTIVE'",
            (rs,i)->new Actor(rs.getString(1),rs.getString(2),
                              rs.getString(3),rs.getString(4)),
            authentication.getName());
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active employee profile required");
        return rows.get(0);
    }

    @GetMapping("/availability/export")
    public List<Slot> export(@RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        return db.query("SELECT a.availability_id,a.employee_id,e.full_name,u.role_id," +
                "a.available_date,a.day_of_week,a.shift,a.start_time,a.end_time," +
                "a.availability_type,a.campus,a.note,UPPER(a.status),a.created_at," +
                "a.reviewed_at,a.reviewed_by FROM availabilities a " +
                "JOIN employees e ON e.employee_id=a.employee_id " +
                "JOIN users u ON u.username=e.username ORDER BY a.availability_id",
            (rs,i)->new Slot(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getObject(5,LocalDate.class),rs.getString(6),
                rs.getString(7),rs.getObject(8,LocalTime.class),
                rs.getObject(9,LocalTime.class),rs.getString(10),rs.getString(11),
                rs.getString(12),rs.getString(13),rs.getTimestamp(14).toInstant(),
                rs.getTimestamp(15)==null?null:rs.getTimestamp(15).toInstant(),
                rs.getString(16),1L,false));
    }

    @PostMapping("/availability/guard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void guardEdit(@RequestHeader(value="X-Internal-Key",required=false) String key,
                          @RequestBody Slot slot) {
        authorize(key);
        if(slot==null || slot.employeeId()==null || slot.id()==null ||
                slot.startTime()==null || slot.endTime()==null ||
                slot.dayOfWeek()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing slot");
        // Resolve ASSIGNED bookings from the authoritative Core schedules,
        // not a possibly lagging availability reference projection.
        Integer teaching=db.queryForObject(
            "SELECT count(*) FROM teaching_schedules t WHERE t.teacher_id=? " +
            "AND t.status='ASSIGNED' AND (t.date=? OR " +
            "(? IS NULL AND lower(to_char(t.date,'dy'))=?)) " +
            "AND t.start_time>=? AND t.end_time<=?",
            Integer.class,slot.employeeId(),slot.availableDate(),
            slot.availableDate(),slot.dayOfWeek().toLowerCase(),
            slot.startTime(),slot.endTime());
        Integer staff=db.queryForObject(
            "SELECT count(*) FROM staff_schedules s WHERE s.employee_id=? " +
            "AND s.status='ASSIGNED' AND (s.date=? OR " +
            "(? IS NULL AND lower(to_char(s.date,'dy'))=?)) " +
            "AND s.start_time>=? AND s.end_time<=?",
            Integer.class,slot.employeeId(),slot.availableDate(),
            slot.availableDate(),slot.dayOfWeek().toLowerCase(),
            slot.startTime(),slot.endTime());
        if((teaching!=null && teaching>0)||(staff!=null && staff>0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cancel existing assignments before changing this availability");
    }

    @PostMapping("/availability/project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void project(@RequestHeader(value="X-Internal-Key",required=false) String key,
                        @RequestBody Slot slot) {
        authorize(key);
        if(slot==null || slot.id()==null || slot.id().isBlank() ||
                slot.id().length()>50 || slot.employeeId()==null ||
                slot.employeeId().length()>50 || slot.version()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid availability projection");
        db.update("INSERT INTO scheduling_availability_versions(availability_id,version) " +
            "VALUES(?,0) ON CONFLICT DO NOTHING",slot.id());
        Long current=db.queryForObject("SELECT version FROM scheduling_availability_versions " +
            "WHERE availability_id=? FOR UPDATE",Long.class,slot.id());
        if(current!=null && current>=slot.version()) return;
        if(slot.deleted()) {
            db.update("DELETE FROM availabilities WHERE availability_id=?",slot.id());
        } else {
            if(slot.startTime()==null || slot.endTime()==null ||
                    !slot.endTime().isAfter(slot.startTime()) ||
                    slot.status()==null || slot.dayOfWeek()==null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid availability time/status");
            db.update("INSERT INTO availabilities (availability_id,employee_id,available_date," +
                "day_of_week,shift,start_time,end_time,availability_type,campus,note,status," +
                "created_at,reviewed_at,reviewed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(availability_id) DO UPDATE SET employee_id=EXCLUDED.employee_id," +
                "available_date=EXCLUDED.available_date,day_of_week=EXCLUDED.day_of_week," +
                "shift=EXCLUDED.shift,start_time=EXCLUDED.start_time,end_time=EXCLUDED.end_time," +
                "availability_type=EXCLUDED.availability_type,campus=EXCLUDED.campus," +
                "note=EXCLUDED.note,status=EXCLUDED.status,reviewed_at=EXCLUDED.reviewed_at," +
                "reviewed_by=EXCLUDED.reviewed_by",
                slot.id(),slot.employeeId(),slot.availableDate(),slot.dayOfWeek(),
                slot.shift(),slot.startTime(),slot.endTime(),slot.availabilityType(),
                slot.campus(),slot.note(),slot.status(),
                java.sql.Timestamp.from(slot.createdAt()==null?Instant.now():slot.createdAt()),
                slot.reviewedAt()==null?null:java.sql.Timestamp.from(slot.reviewedAt()),
                slot.reviewedBy());
        }
        db.update("UPDATE scheduling_availability_versions SET version=?,updated_at=NOW() " +
            "WHERE availability_id=?",slot.version(),slot.id());
    }
}

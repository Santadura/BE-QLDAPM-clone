package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
 * Private Core-to-Scheduling assignment read-model boundary.
 * This NEVER authorizes assignment writes: they remain in Core until
 * Class/CS lifecycle invariants have independent transactional contracts.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments")
@ConditionalOnProperty(name="iig.scheduling.bridge-enabled",havingValue="true")
public class SchedulingAssignmentReadBridge {
    public record Viewer(String employeeId,String username,String role) {}
    public record AssignmentSnapshot(
        String sourceType,String id,String employeeId,String employeeName,
        String staffRole,String classId,String classCode,String className,
        String workplaceId,LocalDate date,LocalTime startTime,LocalTime endTime,
        String workType,String status,String assignedBy,long version,boolean deleted) {}
    public record Acknowledgement(String sourceType,String id,long version) {}
    private record Pending(String sourceType,String id,long version) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public SchedulingAssignmentReadBridge(JdbcTemplate db,
            @Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank()) throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String supplied) {
        byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Scheduling key");
    }

    @GetMapping("/viewer")
    public Viewer viewer(@RequestHeader(value="X-Internal-Key",required=false) String key,
                         Authentication authentication) {
        authorize(key);
        if(authentication==null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Active JWT required");
        List<Viewer> result=db.query(
            "SELECT e.employee_id,u.username,u.role_id FROM users u " +
            "LEFT JOIN employees e ON e.username=u.username AND e.status='ACTIVE' " +
            "WHERE u.username=? AND u.status='ACTIVE'",
            (rs,i)->new Viewer(rs.getString(1),rs.getString(2),rs.getString(3)),
            authentication.getName());
        if(result.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account inactive");
        return result.get(0);
    }

    @GetMapping("/export")
    @Transactional(readOnly=true)
    public List<AssignmentSnapshot> export(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        List<Pending> all=db.query(
            "SELECT source_type,schedule_id,version FROM scheduling_assignment_outbox " +
            "ORDER BY source_type,schedule_id",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),rs.getLong(3)));
        return snapshots(all);
    }

    @GetMapping("/changes")
    @Transactional(readOnly=true)
    public List<AssignmentSnapshot> changes(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam(defaultValue="100") int limit) {
        authorize(key);
        if(limit<1 || limit>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid batch size");
        List<Pending> entries=db.query(
            "SELECT source_type,schedule_id,version FROM scheduling_assignment_outbox " +
            "WHERE delivered_at IS NULL ORDER BY updated_at,source_type,schedule_id LIMIT ?",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),rs.getLong(3)),limit);
        return snapshots(entries);
    }

    @PostMapping("/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestBody Acknowledgement ack) {
        authorize(key);
        if(ack==null || !List.of("TEACHING","STAFF").contains(ack.sourceType())
                || ack.id()==null || ack.id().isBlank() || ack.id().length()>50 || ack.version()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment acknowledgement");
        // Never acknowledge a newer event based on an older relay result.
        db.update("UPDATE scheduling_assignment_outbox SET delivered_at=NOW() " +
            "WHERE source_type=? AND schedule_id=? AND version=? AND delivered_at IS NULL",
            ack.sourceType(),ack.id(),ack.version());
    }

    @PostMapping("/ack/batch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void acknowledgeBatch(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestBody List<Acknowledgement> acks) {
        authorize(key);
        if(acks==null || acks.isEmpty() || acks.size()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid acknowledgement batch");
        for(var ack:acks) {
            if(ack==null || !List.of("TEACHING","STAFF").contains(ack.sourceType())
                    || ack.id()==null || ack.id().isBlank() || ack.id().length()>50
                    || ack.version()<1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid assignment acknowledgement");
        }
        // All-or-none and exact-version to avoid hiding newer unrelayed writes.
        String sql="UPDATE scheduling_assignment_outbox SET delivered_at=NOW() " +
            "WHERE source_type=? AND schedule_id=? AND version=? AND delivered_at IS NULL";
        List<Object[]> args=acks.stream()
            .map(a->new Object[]{a.sourceType(),a.id(),a.version()}).toList();
        db.batchUpdate(sql,args);
    }

    public record ManifestSummary(long rowCount,long versionSum,long pendingCount) {}

    /**
     * Private, cursor-paginated source manifest for read-model cutover checks.
     * Unlike /changes it includes already acknowledged versions, so an
     * accidentally missing Scheduling row cannot be hidden by an empty outbox.
     */
    @GetMapping("/manifest")
    @Transactional(readOnly=true)
    public List<AssignmentSnapshot> manifest(
            @RequestHeader(value="X-Internal-Key",required=false) String key,
            @RequestParam(defaultValue="") String afterSource,
            @RequestParam(defaultValue="") String afterId,
            @RequestParam(defaultValue="250") int limit) {
        authorize(key);
        if(limit<1 || limit>250 || afterId.length()>50 ||
                !(afterSource.isEmpty() || List.of("TEACHING","STAFF").contains(afterSource)) ||
                (afterSource.isEmpty() && !afterId.isEmpty()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment manifest cursor");
        List<Pending> page=db.query(
            "SELECT source_type,schedule_id,version FROM scheduling_assignment_outbox " +
            "WHERE (source_type,schedule_id)>(?,?) " +
            "ORDER BY source_type,schedule_id LIMIT ?",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),rs.getLong(3)),
            afterSource,afterId,limit);
        return snapshots(page);
    }

    @GetMapping("/manifest/summary")
    @Transactional(readOnly=true)
    public ManifestSummary manifestSummary(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        return db.queryForObject(
            "SELECT count(*),COALESCE(sum(version),0)," +
            "count(*) FILTER (WHERE delivered_at IS NULL) " +
            "FROM scheduling_assignment_outbox",
            (rs,i)->new ManifestSummary(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
    }

    private List<AssignmentSnapshot> snapshots(List<Pending> changes) {
        List<AssignmentSnapshot> result=new ArrayList<>(changes.size());
        for(Pending p:changes) {
            List<AssignmentSnapshot> rows;
            if("TEACHING".equals(p.sourceType())) {
                rows=db.query(
                    "SELECT t.teacher_id,e.full_name,t.class_id,c.class_code,c.name," +
                    "t.date,t.start_time,t.end_time,t.status,t.assigned_by " +
                    "FROM teaching_schedules t JOIN employees e ON e.employee_id=t.teacher_id " +
                    "JOIN classes c ON c.class_id=t.class_id WHERE t.teaching_schedule_id=?",
                    (rs,i)->new AssignmentSnapshot("TEACHING",p.id(),rs.getString(1),
                        rs.getString(2),"TEACHER",rs.getString(3),rs.getString(4),
                        rs.getString(5),null,rs.getObject(6,LocalDate.class),
                        rs.getObject(7,LocalTime.class),rs.getObject(8,LocalTime.class),
                        null,rs.getString(9),rs.getString(10),p.version(),false),p.id());
            } else if("STAFF".equals(p.sourceType())) {
                rows=db.query(
                    "SELECT s.employee_id,e.full_name,u.role_id,s.class_id,c.class_code,c.name," +
                    "s.workplace_id,s.date,s.start_time,s.end_time,s.work_type,s.status,s.assigned_by " +
                    "FROM staff_schedules s JOIN employees e ON e.employee_id=s.employee_id " +
                    "JOIN users u ON u.username=e.username " +
                    "LEFT JOIN classes c ON c.class_id=s.class_id WHERE s.staff_schedule_id=?",
                    (rs,i)->new AssignmentSnapshot("STAFF",p.id(),rs.getString(1),
                        rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),
                        rs.getObject(8,LocalDate.class),rs.getObject(9,LocalTime.class),
                        rs.getObject(10,LocalTime.class),rs.getString(11),
                        rs.getString(12),rs.getString(13),p.version(),false),p.id());
            } else throw new IllegalStateException("Unsupported assignment type");
            result.add(rows.isEmpty()?new AssignmentSnapshot(
                p.sourceType(),p.id(),null,null,null,null,null,null,
                null,null,null,null,null,null,null,p.version(),true):rows.get(0));
        }
        return result;
    }
}

package vn.iig.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conflict primitive for the future Scheduling-owned assignment writer.
 *
 * IMPORTANT: During Phase 8b-3b the normalized table is only a Core-backed
 * shadow. This check is NOT allowed to authorize public writes by itself.
 * A future writer must hold this same Scheduling transaction, lock the
 * person/class, verify a live approved availability intent and current
 * Academic session lease, then insert and emit a domain event atomically.
 */
@Service
public class AssignmentWriteConflictGuard {
    public record Candidate(String sourceType,String employeeId,String classId,
                            String exceptId,LocalDate date,LocalTime start,
                            LocalTime end,String reservationId) {}
    private final JdbcTemplate db;

    public AssignmentWriteConflictGuard(JdbcTemplate db) { this.db=db; }

    @Transactional
    public void requireUnoccupied(Candidate c) {
        if(c==null || !Set.of("TEACHING","STAFF").contains(c.sourceType()) ||
                c.employeeId()==null || c.employeeId().isBlank() ||
                c.employeeId().length()>50 || c.date()==null || c.start()==null ||
                c.end()==null || !c.end().isAfter(c.start()) ||
                c.reservationId()==null || c.reservationId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid assignment candidate / reservation");

        // Use the SAME worker advisory lock as Scheduling's availability
        // reservation. A separate class advisory lock is required because
        // different teachers may compete for one class lesson.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",c.employeeId());
        if("TEACHING".equals(c.sourceType())) {
            if(c.classId()==null || c.classId().isBlank() || c.classId().length()>50)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Class required");
            db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",
                "assignment-class:"+c.classId());
        }
        Long intent=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_intent_reservations i " +
            "JOIN scheduling_availability_reservations lease " +
            "ON lease.reservation_id=i.reservation_id " +
            "WHERE i.reservation_id=? AND i.employee_id=? AND i.work_date=? " +
            "AND i.start_time<=? AND i.end_time>=? AND lease.expires_at>NOW()",
            Long.class,c.reservationId(),c.employeeId(),c.date(),c.start(),c.end());
        if(intent==null || intent!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Assignment reservation missing or expired");

        Long person=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_write_shadow " +
            "WHERE deleted=FALSE AND status='ASSIGNED' AND employee_id=? " +
            "AND work_date=? AND start_time<? AND end_time>? " +
            "AND NOT (source_type=? AND schedule_id=?)",
            Long.class,c.employeeId(),c.date(),c.end(),c.start(),
            c.sourceType(),c.exceptId()==null?"":c.exceptId());
        if(person!=null && person>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "EMPLOYEE_SCHEDULE_CONFLICT: Employee is already assigned at this time");

        if("TEACHING".equals(c.sourceType())) {
            Long classTeacher=db.queryForObject(
                "SELECT count(*) FROM scheduling_assignment_write_shadow " +
                "WHERE source_type='TEACHING' AND deleted=FALSE " +
                "AND status='ASSIGNED' AND class_id=? AND work_date=? " +
                "AND start_time<? AND end_time>? AND schedule_id<>?",
                Long.class,c.classId(),c.date(),c.end(),c.start(),
                c.exceptId()==null?"":c.exceptId());
            if(classTeacher!=null && classTeacher>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "CLASS_SCHEDULE_CONFLICT: Class already has a teacher for this slot");
        }
    }
}

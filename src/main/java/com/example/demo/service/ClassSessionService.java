package com.example.demo.service;

import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassSessionService {
    private final JdbcTemplate db;
    private final StudentClassScheduleGuard studentScheduleGuard;

    public record PlannedSessionInput(LocalDate date, LocalTime startTime, LocalTime endTime) {}
    public record RescheduleRequest(
        String originalSessionId,
        LocalDate newDate,
        LocalTime newStartTime,
        LocalTime newEndTime,
        String reason
    ) {}
    private record Slot(LocalDate date, LocalTime start, LocalTime end) {}
    private record Identity(String username, String role) {}

    private Identity identity(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = auth.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5))
                .findFirst().orElse("");
        return new Identity(auth.getName(), role);
    }

    private void requirePlannedSessionMigration() {
        Boolean installed = db.queryForObject(
                "SELECT to_regclass('public.class_sessions') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Database is missing class_sessions. Run npm run db:migrate:deploy " +
                    "against the PostgreSQL database used by Spring Boot, then restart the API.");
        }
    }

    private boolean manages(String classId, Identity actor) {
        if ("ADMIN".equals(actor.role())) return true;
        if (!"CS".equals(actor.role())) return false;
        // A one-off CLASS_SUPPORT shift confers viewing and attendance rights,
        // never permission to rewrite the entire class timetable.
        Integer count = db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "JOIN classes c ON c.class_id=? " +
            "JOIN class_access_scopes a ON a.employee_id=e.employee_id AND a.class_id=c.class_id " +
            "WHERE e.username=? AND e.status='ACTIVE' AND u.status='ACTIVE' " +
            "AND a.status='ACTIVE' AND (a.source IN " +
            "('CM_ASSIGNED_COURSE','ASSIGNED_SCOPE','AUTO_ASSIGNED') " +
            "OR (a.source='CREATED_BY_CS' AND c.status IN ('DRAFT','READY')))",
            Integer.class, classId, actor.username());
        return count != null && count > 0;
    }

    private boolean canView(String classId, Identity actor) {
        if (manages(classId, actor) || Set.of("TC", "CM").contains(actor.role())) return true;
        if ("CS".equals(actor.role())) {
            Integer count = db.queryForObject(
                "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
                "WHERE e.username=? AND e.status='ACTIVE' AND u.status='ACTIVE' AND (" +
                "EXISTS(SELECT 1 FROM class_access_scopes a WHERE a.employee_id=e.employee_id " +
                "AND a.class_id=? AND a.status='ACTIVE') OR " +
                "EXISTS(SELECT 1 FROM staff_schedules s WHERE s.employee_id=e.employee_id " +
                "AND s.class_id=? AND s.work_type='CLASS_SUPPORT' AND s.status='ASSIGNED'))",
                Integer.class, actor.username(), classId, classId);
            return count != null && count > 0;
        }
        if (!"TEACHER".equals(actor.role())) return false;
        Integer count = db.queryForObject(
            "SELECT count(*) FROM teaching_schedules t JOIN employees e ON t.teacher_id=e.employee_id " +
            "WHERE t.class_id=? AND t.status='ASSIGNED' AND e.username=?",
            Integer.class, classId, actor.username());
        return count != null && count > 0;
    }

    public List<Map<String,Object>> listClassPlanning(Authentication auth) {
        Identity actor = identity(auth);
        if (!Set.of("TC","CM","ADMIN").contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Planning list is only for TC, CM or Admin");
        }
        // Exclude student roster and sensitive student details.
        return db.queryForList(
            "SELECT c.class_id AS \"classId\", c.course_id AS \"courseId\", c.class_code AS \"classCode\", " +
            "c.name, c.start_date AS \"startDate\", c.end_date AS \"endDate\", " +
            "c.status, cr.name AS \"courseName\" " +
            "FROM classes c JOIN courses cr ON cr.course_id=c.course_id " +
            "WHERE c.status IN ('READY','RUNNING') ORDER BY c.start_date,c.class_code");
    }

    public List<Map<String,Object>> sessions(String classId, Authentication auth) {
        Identity actor = identity(auth);
        if (!canView(classId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No access to class planned sessions");
        }
        requirePlannedSessionMigration();
        return readSessions(classId);
    }

    private List<Map<String,Object>> readSessions(String classId) {
        return db.queryForList(
            "SELECT class_session_id AS id, class_id AS \"classId\", date, " +
            "start_time AS \"startTime\", end_time AS \"endTime\", status " +
            "FROM class_sessions WHERE class_id=? ORDER BY date,start_time",
            classId);
    }

    @Transactional
    public List<Map<String,Object>> reschedule(
            String classId, RescheduleRequest input, Authentication auth) {
        Identity actor = identity(auth);
        if (!manages(classId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only Admin or managing CS can reschedule class sessions");
        }
        requirePlannedSessionMigration();
        if (input == null || input.originalSessionId() == null ||
                input.reason() == null || input.reason().trim().length() < 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Original lesson and a reason of at least five characters are required");
        }

        // Serialize with staff assignment, timetable updates and class state transitions.
        List<Map<String,Object>> classes = db.queryForList(
            "SELECT start_date,end_date,status FROM classes WHERE class_id=? FOR UPDATE",
            classId);
        if (classes.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Class not found");
        Map<String,Object> cls = classes.get(0);
        if (!"RUNNING".equals(cls.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Use the planned timetable editor before the class starts");
        }

        List<Map<String,Object>> oldRows = db.queryForList(
            "SELECT date,start_time,end_time FROM class_sessions " +
            "WHERE class_session_id=? AND class_id=? AND status='PLANNED' FOR UPDATE",
            input.originalSessionId(), classId);
        if (oldRows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Active planned lesson not found");
        }
        Map<String,Object> old = oldRows.get(0);
        LocalDate oldDate = ((Date)old.get("date")).toLocalDate();
        LocalTime oldStart = LocalTime.parse(old.get("start_time").toString());
        LocalTime oldEnd = LocalTime.parse(old.get("end_time").toString());
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        if (!LocalDateTime.of(oldDate, oldStart).isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only future lessons may be cancelled or rescheduled");
        }

        boolean makeUp = input.newDate() != null;
        if (makeUp != (input.newStartTime() != null && input.newEndTime() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Provide a complete replacement date/time, or leave all three blank for cancellation");
        }
        if (makeUp) {
            LocalDate start = ((Date)cls.get("start_date")).toLocalDate();
            LocalDate end = ((Date)cls.get("end_date")).toLocalDate();
            if (!LocalDateTime.of(input.newDate(), input.newStartTime()).isAfter(now) ||
                    input.newDate().isBefore(start) || input.newDate().isAfter(end) ||
                    !input.newEndTime().isAfter(input.newStartTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Make-up lesson must be in the future and within class date range");
            }
            Integer clash = db.queryForObject(
                "SELECT count(*) FROM class_sessions WHERE class_id=? AND status='PLANNED' " +
                "AND class_session_id<>? AND date=? AND start_time<? AND end_time>?",
                Integer.class, classId, input.originalSessionId(), input.newDate(),
                input.newEndTime(), input.newStartTime());
            if (clash != null && clash > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Make-up lesson overlaps another planned class lesson");
            }
        }

        // The newly proposed make-up slot must not collide with any existing
        // scheduled session, including one whose time was already cancelled.
        if (makeUp) {
            Integer alreadyExists = db.queryForObject(
                "SELECT count(*) FROM class_sessions WHERE class_id=? AND date=? " +
                "AND start_time=? AND end_time=?",
                Integer.class, classId, input.newDate(), input.newStartTime(), input.newEndTime());
            if (alreadyExists != null && alreadyExists > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "Make-up lesson already exists; choose another slot");
            }
        }

        if (makeUp) {
            studentScheduleGuard.lockClassStudents(classId);
            studentScheduleGuard.validateTimetable(classId, List.of(
                new StudentClassScheduleGuard.Slot(
                    input.newDate(), input.newStartTime(), input.newEndTime())));
        }

        // Cancel only assignments belonging to this one lesson, keeping payroll/audit history.
        db.update("UPDATE teaching_schedules SET status='CANCELLED' " +
                  "WHERE class_id=? AND date=? AND start_time=? AND end_time=? AND status='ASSIGNED'",
                  classId, oldDate, oldStart, oldEnd);
        db.update("UPDATE staff_schedules SET status='CANCELLED' " +
                  "WHERE class_id=? AND work_type='CLASS_SUPPORT' " +
                  "AND date=? AND start_time=? AND end_time=? AND status='ASSIGNED'",
                  classId, oldDate, oldStart, oldEnd);
        // Rescheduling can cancel the course manager's last support shift.
        // Retain creator visibility, but revoke outdated full-class edit rights.
        db.update(
            "UPDATE class_access_scopes scope SET " +
            "status=CASE WHEN c.created_by=e.username THEN 'ACTIVE' ELSE 'INACTIVE' END, " +
            "source=CASE WHEN c.created_by=e.username THEN 'CREATED_BY_CS' " +
            "ELSE 'CM_ASSIGNED_COURSE' END FROM classes c, employees e " +
            "WHERE scope.class_id=c.class_id AND scope.employee_id=e.employee_id " +
            "AND scope.class_id=? AND scope.status='ACTIVE' " +
            "AND scope.source IN ('CM_ASSIGNED_COURSE','ASSIGNED_SCOPE','AUTO_ASSIGNED') " +
            "AND NOT EXISTS (SELECT 1 FROM staff_schedules ss " +
            "WHERE ss.class_id=scope.class_id AND ss.employee_id=scope.employee_id " +
            "AND ss.work_type='CLASS_SUPPORT' AND ss.status='ASSIGNED')",
            classId);
        db.update("UPDATE class_sessions SET status='CANCELLED' WHERE class_session_id=?",
                  input.originalSessionId());
        if (makeUp) {
            db.update("INSERT INTO class_sessions " +
                      "(class_session_id,class_id,date,start_time,end_time,status) " +
                      "VALUES (?,?,?,?,?,'PLANNED')",
                      UUID.randomUUID().toString(), classId, input.newDate(),
                      input.newStartTime(), input.newEndTime());
        }
        db.update("INSERT INTO audit_logs " +
                  "(audit_log_id,username,action,entity_type,entity_id,description) " +
                  "VALUES (?,?,'RESCHEDULE_CLASS_LESSON','CLASS',?,?)",
                  UUID.randomUUID().toString(), actor.username(), classId,
                  input.reason().trim() + ": " + oldDate + " " + oldStart + "-" + oldEnd +
                  (makeUp ? " -> " + input.newDate() + " " + input.newStartTime() +
                  "-" + input.newEndTime() : " -> CANCELLED"));
        // Authorization is evaluated at transaction entry. A reschedule may
        // cancel the caller's only support shift and must not invalidate the
        // already-authorized transaction while returning its result.
        return readSessions(classId);
    }

    @Transactional
    public List<Map<String,Object>> replace(String classId, List<PlannedSessionInput> input, Authentication auth) {
        Identity actor = identity(auth);
        if (!manages(classId, actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin or managing CS can plan class sessions");
        }
        requirePlannedSessionMigration();
        List<Map<String,Object>> classes = db.queryForList(
            "SELECT class_id, start_date, end_date, status FROM classes WHERE class_id=? FOR UPDATE", classId);
        if (classes.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Class not found");
        Map<String,Object> row = classes.get(0);
        if (!Set.of("DRAFT", "READY", "RUNNING").contains(row.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Plan cannot change after class completion");
        }
        boolean running = "RUNNING".equals(row.get("status"));
        if (input == null || input.size() > 400) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Plan must contain at most 400 lessons");
        }
        if ("READY".equals(row.get("status")) && input.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A Ready class must retain at least one planned lesson");
        }
        LocalDate start = ((Date)row.get("start_date")).toLocalDate();
        LocalDate end = ((Date)row.get("end_date")).toLocalDate();
        List<Slot> desired = new ArrayList<>();
        for (PlannedSessionInput item : input) {
            if (item == null || item.date() == null || item.startTime() == null || item.endTime() == null ||
                    !item.endTime().isAfter(item.startTime()) ||
                    item.date().isBefore(start) || item.date().isAfter(end)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Planned lessons must be valid and within the class dates");
            }
            Slot slot = new Slot(item.date(), item.startTime(), item.endTime());
            if (desired.contains(slot)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate lesson in plan");
            }
            for (Slot other : desired) {
                if (other.date().equals(slot.date()) &&
                        other.start().isBefore(slot.end()) && other.end().isAfter(slot.start())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Class lessons cannot overlap");
                }
            }
            desired.add(slot);
        }

        studentScheduleGuard.lockClassStudents(classId);
        studentScheduleGuard.validateTimetable(classId, desired.stream()
            .map(slot -> new StudentClassScheduleGuard.Slot(
                    slot.date(),slot.start(),slot.end()))
            .toList());

        List<Map<String,Object>> assigned = db.queryForList(
            "SELECT date,start_time,end_time FROM teaching_schedules " +
            "WHERE class_id=? AND status='ASSIGNED' UNION ALL " +
            "SELECT date,start_time,end_time FROM staff_schedules " +
            "WHERE class_id=? AND status='ASSIGNED' AND work_type='CLASS_SUPPORT'", classId, classId);
        for (Map<String,Object> existing : assigned) {
            Slot slot = new Slot(((Date)existing.get("date")).toLocalDate(),
                    LocalTime.parse(existing.get("start_time").toString()), LocalTime.parse(existing.get("end_time").toString()));
            if (!desired.contains(slot)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Cannot remove or change a lesson with assigned Teacher/CS. Cancel assignments first.");
            }
        }

        List<Map<String,Object>> previous = db.queryForList(
            "SELECT class_session_id, date,start_time,end_time FROM class_sessions " +
            "WHERE class_id=? AND status='PLANNED' FOR UPDATE", classId);
        Set<Slot> old = previous.stream()
                .map(item -> new Slot(((Date)item.get("date")).toLocalDate(),
                        LocalTime.parse(item.get("start_time").toString()), LocalTime.parse(item.get("end_time").toString())))
                .collect(Collectors.toSet());
        if (running) {
            // Extending a running course is append-only: no existing planned lesson
            // is allowed to vanish or change time. Past lessons remain historical.
            if (!desired.containsAll(old)) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "Running class timetable is append-only; reschedule a future lesson using the dedicated workflow");
            }
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
            for (Slot slot : desired) {
                if (old.contains(slot)) continue;
                if (!LocalDateTime.of(slot.date(), slot.start()).isAfter(now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "New lessons on a Running class must start in the future");
                }
                Integer cancelled = db.queryForObject(
                    "SELECT count(*) FROM class_sessions WHERE class_id=? AND date=? " +
                    "AND start_time=? AND end_time=? AND status='CANCELLED'",
                    Integer.class, classId, slot.date(), slot.start(), slot.end());
                if (cancelled != null && cancelled > 0) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "A cancelled lesson cannot be reactivated; create another future slot");
                }
            }
        }
        if (!"DRAFT".equals(row.get("status"))) {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
            for (Map<String,Object> item : previous) {
                Slot slot = new Slot(((Date)item.get("date")).toLocalDate(),
                        LocalTime.parse(item.get("start_time").toString()),
                        LocalTime.parse(item.get("end_time").toString()));
                if (!desired.contains(slot) &&
                    !LocalDateTime.of(slot.date(), slot.start()).isAfter(now)) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "Past class lessons cannot be removed; preserve the academic and staffing history");
                }
            }
        }
        for (Map<String,Object> item : previous) {
            Slot slot = new Slot(((Date)item.get("date")).toLocalDate(),
                    LocalTime.parse(item.get("start_time").toString()), LocalTime.parse(item.get("end_time").toString()));
            if (!desired.contains(slot)) db.update("DELETE FROM class_sessions WHERE class_session_id=? AND status='PLANNED'",
                    item.get("class_session_id"));
        }
        for (Slot slot : desired) {
            if (!old.contains(slot)) {
                int revived = db.update(
                        "UPDATE class_sessions SET status='PLANNED' " +
                        "WHERE class_id=? AND date=? AND start_time=? AND end_time=? AND status='CANCELLED'",
                        classId, slot.date(), slot.start(), slot.end());
                if (revived == 0) {
                    db.update("INSERT INTO class_sessions (class_session_id,class_id,date,start_time,end_time,status) " +
                            "VALUES (?,?,?,?,?,'PLANNED')",
                            UUID.randomUUID().toString(), classId, slot.date(), slot.start(), slot.end());
                }
            }
        }
        db.update("INSERT INTO audit_logs (audit_log_id,username,action,entity_type,entity_id,description) " +
                "VALUES (?,?, 'UPDATE_CLASS_PLAN','CLASS',?,?)",
                UUID.randomUUID().toString(), actor.username(), classId,
                "Updated planned lessons: " + desired.size());
        // Authorization is evaluated at transaction entry. A reschedule may
        // cancel the caller's only support shift and must not invalidate the
        // already-authorized transaction while returning its result.
        return readSessions(classId);
    }
}

package com.example.demo.service;

import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The source of truth for a CS's confirmation of a teacher's presence.
 * A teaching assignment by itself never counts as attendance.
 * Payroll can later join teacher_session_attendance to teaching_schedules;
 * this service deliberately does not calculate work hours or wages.
 */
@Service
@RequiredArgsConstructor
public class TeacherSessionAttendanceService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> STATUSES = Set.of("PRESENT", "ABSENT", "LATE");
    private final JdbcTemplate db;
    private final SchedulingAccess access;

    public record MarkRequest(String status) {}

    static String checkedStatus(String input) {
        String status = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Attendance status must be PRESENT, ABSENT or LATE");
        }
        return status;
    }

    static boolean lessonHasStarted(LocalDate date, LocalTime start, LocalDateTime now) {
        return !LocalDateTime.of(date, start).isAfter(now);
    }

    private boolean canViewClass(String employeeId, String classId) {
        Integer count = db.queryForObject(
            "SELECT count(*) FROM classes c WHERE c.class_id=? AND (" +
            "EXISTS (SELECT 1 FROM class_access_scopes a WHERE a.class_id=c.class_id " +
            "AND a.employee_id=? AND a.status='ACTIVE') OR " +
            "EXISTS (SELECT 1 FROM staff_schedules s WHERE s.class_id=c.class_id " +
            "AND s.employee_id=? AND s.work_type='CLASS_SUPPORT' AND s.status='ASSIGNED'))",
            Integer.class, classId, employeeId, employeeId);
        return count != null && count > 0;
    }

    public List<Map<String,Object>> list(String classId) {
        var actor = access.requireRole("CS");
        if (!canViewClass(actor.id(), classId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "You are not assigned to manage or support this class");
        }
        return db.queryForList(
            "SELECT t.teaching_schedule_id AS id, t.class_id AS \"classId\", " +
            "t.teacher_id AS \"teacherId\", e.full_name AS \"teacherName\", " +
            "t.status AS \"assignmentStatus\", " +
            "t.date, t.start_time AS \"startTime\", t.end_time AS \"endTime\", " +
            "a.status AS \"attendanceStatus\", a.recorded_at AS \"recordedAt\", " +
            "a.updated_at AS \"updatedAt\", recorder.full_name AS \"recordedByName\", " +
            "EXISTS (SELECT 1 FROM staff_schedules s WHERE s.employee_id=? " +
            "AND s.class_id=t.class_id AND s.date=t.date " +
            "AND s.start_time=t.start_time AND s.end_time=t.end_time " +
            "AND s.work_type='CLASS_SUPPORT' AND s.status='ASSIGNED') AS \"assignedToMe\" " +
            "FROM teaching_schedules t " +
            "JOIN employees e ON e.employee_id=t.teacher_id " +
            "JOIN class_sessions lesson ON lesson.class_id=t.class_id " +
            "AND lesson.date=t.date AND lesson.start_time=t.start_time " +
            "AND lesson.end_time=t.end_time " +
            "AND lesson.status IN ('PLANNED','CANCELLED') " +
            "LEFT JOIN teacher_session_attendance a ON a.teaching_schedule_id=t.teaching_schedule_id " +
            "LEFT JOIN employees recorder ON recorder.employee_id=a.recorded_by " +
            "WHERE t.class_id=? AND ((t.status='ASSIGNED' AND lesson.status='PLANNED') " +
            "OR (t.status='CANCELLED' AND a.attendance_id IS NOT NULL)) " +
            "ORDER BY t.date,t.start_time,CASE WHEN t.status='CANCELLED' THEN 0 ELSE 1 END,e.full_name",
            actor.id(), classId);
    }

    @Transactional
    public Map<String,Object> mark(String classId, String teachingScheduleId, MarkRequest request) {
        var actor = access.requireRole("CS");
        String status = checkedStatus(request == null ? null : request.status());

        List<Map<String,Object>> matches = db.queryForList(
            "SELECT t.date, t.start_time, t.end_time, t.status, a.status AS attendance_status " +
            "FROM teaching_schedules t " +
            "JOIN class_sessions lesson ON lesson.class_id=t.class_id " +
            "AND lesson.date=t.date AND lesson.start_time=t.start_time " +
            "AND lesson.end_time=t.end_time AND lesson.status='PLANNED' " +
            "LEFT JOIN teacher_session_attendance a ON a.teaching_schedule_id=t.teaching_schedule_id " +
            "WHERE t.teaching_schedule_id=? AND t.class_id=? AND t.status='ASSIGNED' " +
            "FOR UPDATE OF t",
            teachingScheduleId, classId);
        if (matches.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Active teacher assignment not found in a planned class lesson");
        }

        Integer supportCount = db.queryForObject(
            "SELECT count(*) FROM staff_schedules s " +
            "JOIN teaching_schedules t ON t.teaching_schedule_id=? " +
            "WHERE s.employee_id=? AND s.class_id=t.class_id AND s.date=t.date " +
            "AND s.start_time=t.start_time AND s.end_time=t.end_time " +
            "AND s.work_type='CLASS_SUPPORT' AND s.status='ASSIGNED'",
            Integer.class, teachingScheduleId, actor.id());
        if (supportCount == null || supportCount == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only the CS assigned to support this exact lesson can mark attendance");
        }

        Map<String,Object> lesson = matches.get(0);
        LocalDate date = ((Date)lesson.get("date")).toLocalDate();
        LocalTime start = LocalTime.parse(String.valueOf(lesson.get("start_time")));
        if (!lessonHasStarted(date, start, LocalDateTime.now(BUSINESS_ZONE))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Attendance can only be recorded once the lesson has started");
        }

        if (!status.equals(lesson.get("attendance_status"))) {
            db.update(
                "INSERT INTO teacher_session_attendance " +
                "(attendance_id,teaching_schedule_id,status,recorded_by) VALUES (?,?,?,?) " +
                "ON CONFLICT (teaching_schedule_id) DO UPDATE SET " +
                "status=EXCLUDED.status, recorded_by=EXCLUDED.recorded_by, updated_at=now()",
                UUID.randomUUID().toString(), teachingScheduleId, status, actor.id());
            access.audit(actor, "RECORD_TEACHER_ATTENDANCE_" + status,
                    "TEACHING_SCHEDULE", teachingScheduleId);
        }

        return db.queryForMap(
            "SELECT a.attendance_id AS id, a.teaching_schedule_id AS \"teachingScheduleId\", " +
            "a.status, a.recorded_at AS \"recordedAt\", a.updated_at AS \"updatedAt\", " +
            "e.full_name AS \"recordedByName\" " +
            "FROM teacher_session_attendance a " +
            "JOIN employees e ON e.employee_id=a.recorded_by WHERE a.teaching_schedule_id=?",
            teachingScheduleId);
    }
}

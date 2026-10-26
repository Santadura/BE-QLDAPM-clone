package com.example.demo.service;

import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Enforces the preconditions for transitions that affect CS accountability
 * and the teacher attendance records later used by Payroll.
 */
@Service
@RequiredArgsConstructor
public class ClassLifecycleIntegrityService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate db;

    public void requireCsCoverageBeforeRunning(String classId) {
        Integer missing = db.queryForObject(
            "SELECT count(*) FROM class_sessions cs WHERE cs.class_id=? " +
            "AND cs.status='PLANNED' AND NOT EXISTS (" +
            "SELECT 1 FROM staff_schedules ss WHERE ss.class_id=cs.class_id " +
            "AND ss.date=cs.date AND ss.start_time=cs.start_time " +
            "AND ss.end_time=cs.end_time AND ss.work_type='CLASS_SUPPORT' " +
            "AND ss.status='ASSIGNED')",
            Integer.class, classId);
        if (missing == null || missing > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot start class: " + missing +
                " planned lesson(s) lack a CM-assigned CS support shift. " +
                "Assign CS before moving to RUNNING.");
        }
    }

    public void requireTeacherAttendanceBeforeCompleted(String classId) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        Integer unmarked = db.queryForObject(
            "SELECT count(*) FROM teaching_schedules ts " +
            "JOIN class_sessions lesson ON lesson.class_id=ts.class_id " +
            "AND lesson.date=ts.date AND lesson.start_time=ts.start_time " +
            "AND lesson.end_time=ts.end_time AND lesson.status='PLANNED' " +
            "LEFT JOIN teacher_session_attendance ta " +
            "ON ta.teaching_schedule_id=ts.teaching_schedule_id " +
            "WHERE ts.class_id=? AND ts.status='ASSIGNED' " +
            "AND (ts.date < ? OR (ts.date=? AND ts.start_time<=?)) " +
            "AND ta.attendance_id IS NULL",
            Integer.class, classId, today, today, now);
        if (unmarked != null && unmarked > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Cannot complete class: " + unmarked +
                " teacher lesson(s) are missing CS attendance. " +
                "Record PRESENT, ABSENT or LATE for each taught lesson first.");
        }
    }
}

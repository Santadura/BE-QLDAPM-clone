package com.example.demo.service;

import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Student lesson conflict policy. Compare actual PLANNED lesson timestamps,
 * not merely the start/end date of a course or its weekly pattern.
 *
 * Callers serialize on students (FOR UPDATE) before writing roster or timetable.
 */
@Service
@RequiredArgsConstructor
public class StudentClassScheduleGuard {
    private final JdbcTemplate db;

    public record Slot(LocalDate date, LocalTime start, LocalTime end) {}

    private static boolean overlaps(LocalTime leftStart, LocalTime leftEnd,
                                    LocalTime rightStart, LocalTime rightEnd) {
        return leftStart.isBefore(rightEnd) && leftEnd.isAfter(rightStart);
    }

    public String enrollmentConflict(String studentId, String classId) {
        List<Map<String,Object>> result = db.queryForList(
            "SELECT other_class.class_code AS \"otherClass\", proposed.date, " +
            "proposed.start_time AS \"startTime\", proposed.end_time AS \"endTime\" " +
            "FROM class_sessions proposed " +
            "JOIN class_sessions occupied ON occupied.date=proposed.date " +
            "AND occupied.start_time<proposed.end_time AND occupied.end_time>proposed.start_time " +
            "AND occupied.status='PLANNED' AND occupied.class_id<>proposed.class_id " +
            "JOIN class_students member ON member.class_id=occupied.class_id " +
            "AND member.student_id=? AND member.status='ACTIVE' " +
            "JOIN classes other_class ON other_class.class_id=occupied.class_id " +
            "AND other_class.status IN ('DRAFT','READY','RUNNING') " +
            "WHERE proposed.class_id=? AND proposed.status='PLANNED' " +
            "ORDER BY proposed.date,proposed.start_time LIMIT 1",
            studentId, classId);
        return result.isEmpty() ? null : describe(result.get(0));
    }

    /**
     * Validate a proposed timetable against lessons that the class's ACTIVE
     * students already attend in other active classes. Includes newly added
     * and changed lessons; no temporary partial plan must be committed.
     */
    public void validateTimetable(String classId, List<Slot> proposed) {
        if (proposed == null || proposed.isEmpty()) return;
        for (Slot slot : proposed) {
            List<Map<String,Object>> conflicts = db.queryForList(
                "SELECT other_class.class_code AS \"otherClass\", " +
                "s.student_code AS \"studentCode\" " +
                "FROM class_students own " +
                "JOIN students s ON s.student_id=own.student_id " +
                "JOIN class_students other ON other.student_id=own.student_id " +
                "AND other.class_id<>own.class_id AND other.status='ACTIVE' " +
                "JOIN classes other_class ON other_class.class_id=other.class_id " +
                "AND other_class.status IN ('DRAFT','READY','RUNNING') " +
                "JOIN class_sessions occupied ON occupied.class_id=other.class_id " +
                "AND occupied.status='PLANNED' AND occupied.date=? " +
                "AND occupied.start_time<? AND occupied.end_time>? " +
                "WHERE own.class_id=? AND own.status='ACTIVE' LIMIT 1",
                slot.date(),slot.end(),slot.start(),classId);
            if (!conflicts.isEmpty()) {
                Map<String,Object> first = conflicts.get(0);
                throw new ApiException(HttpStatus.CONFLICT,
                    "STUDENT_SCHEDULE_CONFLICT: student " + first.get("studentCode") +
                    " already has a lesson in " + first.get("otherClass") + " on " +
                    slot.date() + " " + slot.start() + "-" + slot.end());
            }
        }
    }

    public void lockClassStudents(String classId) {
        // Canonical order prevents concurrent timetable edits/enrollments from
        // silently creating clashes for the same student.
        db.queryForList(
            "SELECT s.student_id FROM students s " +
            "JOIN class_students own ON own.student_id=s.student_id " +
            "AND own.class_id=? AND own.status='ACTIVE' " +
            "ORDER BY s.student_id FOR UPDATE OF s", classId);
    }

    private static String describe(Map<String,Object> row) {
        return "Student already has a class lesson at " + row.get("date") +
                " " + row.get("startTime") + "-" + row.get("endTime") +
                " in class " + row.get("otherClass");
    }

    static boolean timesOverlap(LocalTime aStart, LocalTime aEnd,
                                LocalTime bStart, LocalTime bEnd) {
        return overlaps(aStart,aEnd,bStart,bEnd);
    }
}

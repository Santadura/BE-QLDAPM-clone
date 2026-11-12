package com.example.demo.service;

import com.example.demo.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned Academic lesson, teacher and exact-CS support facts, still
 * authored by Core. The token is an optimistic precondition, not
 * permission to use an independent Attendance writer.
 */
@Service
public class TeacherAttendanceLiveFactsService {
    private final JdbcTemplate db;
    private final SchedulingAccess access;

    public TeacherAttendanceLiveFactsService(JdbcTemplate db,SchedulingAccess access) {
        this.db=db; this.access=access;
    }

    private record Lesson(String sessionId,String teacherId,long classVersion,
        String classStatus,long sessionVersion,LocalDate date,
        LocalTime start,LocalTime end,long teacherVersion) {}
    private record Support(String id,long version) {}
    private record Modes(String attendanceMode,long attendanceGeneration,
        String schedulingMode,long schedulingGeneration,
        String academicMode,long academicGeneration) {}

    public record Facts(String classId,String teachingScheduleId,String sessionId,
        long classVersion,long sessionVersion,long teacherVersion,
        String supportScheduleId,long supportVersion,String actorEmployeeId,
        LocalDate date,LocalTime startTime,LocalTime endTime,
        long attendanceGeneration,long schedulingGeneration,String token) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Facts current(String classId,String scheduleId) {
        return compute(classId,scheduleId,false,access.requireRole("CS"));
    }

    /** Only a private native admission may use the fenced ATTENDANCE mode. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Facts nativeCurrent(String classId,String scheduleId) {
        return compute(classId,scheduleId,true,access.requireRole("CS"));
    }

    /**
     * Core's private projection consumer has no end-user bearer after a
     * network timeout. The earlier actor-bound immutable Core claim is its
     * authority, but current CS account and exact support still MUST agree.
     * A committed duplicate receipt is handled before reaching this method.
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Facts nativeForClaim(String classId,String scheduleId,
            String actorUsername,String actorEmployeeId) {
        if(actorUsername==null || actorEmployeeId==null ||
            !actorUsername.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !actorEmployeeId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Exact native Attendance claim identity required");
        Long active=db.queryForObject(
            "SELECT COUNT(*) FROM users u JOIN employees e "+
            "ON e.username=u.username WHERE u.username=? AND e.employee_id=? "+
            "AND u.status='ACTIVE' AND e.status='ACTIVE' AND u.role_id='CS'",
            Long.class,actorUsername,actorEmployeeId);
        if(!Long.valueOf(1).equals(active))
            throw new ApiException(HttpStatus.FORBIDDEN,
                "Native Attendance reference CS role has been revoked");
        return compute(classId,scheduleId,true,
            new SchedulingAccess.StaffIdentity(actorEmployeeId,actorUsername,"CS"));
    }

    private Facts compute(String classId,String scheduleId,boolean nativeWriter,
            SchedulingAccess.StaffIdentity actor) {
        if(classId==null || classId.isBlank() || classId.length()>50 ||
                scheduleId==null || scheduleId.isBlank() || scheduleId.length()>50)
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Class and teaching schedule identifiers required");

        List<Lesson> lessons=db.query(
            "SELECT lesson.class_session_id,t.teacher_id,c.scheduling_version," +
            "c.status,lesson.scheduling_version,t.date,t.start_time,t.end_time," +
            "COALESCE(revision.version,0) " +
            "FROM teaching_schedules t " +
            "JOIN classes c ON c.class_id=t.class_id " +
            "JOIN class_sessions lesson ON lesson.class_id=t.class_id " +
            "AND lesson.date=t.date AND lesson.start_time=t.start_time " +
            "AND lesson.end_time=t.end_time AND lesson.status='PLANNED' " +
            "LEFT JOIN scheduling_assignment_outbox revision " +
            "ON revision.source_type='TEACHING' " +
            "AND revision.schedule_id=t.teaching_schedule_id " +
            "WHERE t.teaching_schedule_id=? AND t.class_id=? AND t.status='ASSIGNED'",
            (rs,i)->new Lesson(rs.getString(1),rs.getString(2),rs.getLong(3),
                rs.getString(4),rs.getLong(5),
                rs.getObject(6,LocalDate.class),rs.getObject(7,LocalTime.class),
                rs.getObject(8,LocalTime.class),rs.getLong(9)),scheduleId,classId);
        if(lessons.size()!=1)
            throw new ApiException(HttpStatus.NOT_FOUND,
                "Active teacher and planned lesson not found");
        Lesson lesson=lessons.get(0);
        List<Support> shifts=db.query(
            "SELECT s.staff_schedule_id,COALESCE(revision.version,0) " +
            "FROM staff_schedules s " +
            "LEFT JOIN scheduling_assignment_outbox revision " +
            "ON revision.source_type='STAFF' AND revision.schedule_id=s.staff_schedule_id " +
            "WHERE s.employee_id=? AND s.class_id=? AND s.date=? " +
            "AND s.start_time=? AND s.end_time=? AND " +
            "s.work_type='CLASS_SUPPORT' AND s.status='ASSIGNED' " +
            "ORDER BY s.staff_schedule_id LIMIT 1",
            (rs,i)->new Support(rs.getString(1),rs.getLong(2)),
            actor.id(),classId,lesson.date(),lesson.start(),lesson.end());
        if(shifts.size()!=1)
            throw new ApiException(HttpStatus.FORBIDDEN,
                "Only CS supporting this exact lesson may mark attendance");
        Support support=shifts.get(0);
        Modes modes=db.queryForObject(
            "SELECT a.writer_mode,a.generation,s.writer_mode,s.writer_generation,"+
            "academic.writer_mode,academic.generation "+
            "FROM teacher_attendance_writer_control a "+
            "JOIN scheduling_assignment_writer_control s ON s.singleton=TRUE "+
            "JOIN academic_session_writer_control academic ON academic.singleton=TRUE "+
            "WHERE a.singleton=TRUE",
            (rs,i)->new Modes(rs.getString(1),rs.getLong(2),
                rs.getString(3),rs.getLong(4),rs.getString(5),rs.getLong(6)));
        if(modes==null ||
            (nativeWriter
                ? !("ATTENDANCE".equals(modes.attendanceMode()) &&
                    "QUIESCED".equals(modes.schedulingMode()) &&
                    "QUIESCED".equals(modes.academicMode()))
                : !("CORE".equals(modes.attendanceMode()) &&
                    "CORE".equals(modes.schedulingMode()))))
            throw new ApiException(HttpStatus.CONFLICT,
                "Core Attendance owner or dependency writer generation is incompatible");
        if(nativeWriter && java.time.LocalDateTime.of(
                lesson.date(),lesson.start()).isAfter(
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))))
            throw new ApiException(HttpStatus.CONFLICT,
                "Native Attendance requires a lesson that has already started");
        if(lesson.classVersion()<1 || lesson.sessionVersion()<1 ||
                lesson.teacherVersion()<1 || support.version()<1 ||
                modes.attendanceGeneration()<1 || modes.schedulingGeneration()<1 ||
                modes.academicGeneration()<1)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Versioned Academic/Scheduling facts incomplete");
        String canonical=String.join("\n","attendance-facts-v1",
            actor.username(),actor.id(),classId,scheduleId,
            lesson.sessionId(),lesson.teacherId(),lesson.classStatus(),
            String.valueOf(lesson.classVersion()),String.valueOf(lesson.sessionVersion()),
            String.valueOf(lesson.teacherVersion()),support.id(),
            String.valueOf(support.version()),lesson.date().toString(),
            lesson.start().toString(),lesson.end().toString(),
            String.valueOf(modes.attendanceGeneration()),
            String.valueOf(modes.schedulingGeneration()),
            modes.academicMode(),String.valueOf(modes.academicGeneration()));
        return new Facts(classId,scheduleId,lesson.sessionId(),
            lesson.classVersion(),lesson.sessionVersion(),lesson.teacherVersion(),
            support.id(),support.version(),actor.id(),lesson.date(),
            lesson.start(),lesson.end(),modes.attendanceGeneration(),
            modes.schedulingGeneration(),sha256(canonical));
    }

    /**
     * Recompute after the original mark() acquires class, lesson, teacher and
     * exact CS shift locks. Caller rolls back the attendance + audit + source
     * outbox transaction if any reference version or owner changed.
     */
    public void requireUnchanged(String classId,String scheduleId,String token) {
        if(token==null) return; // Legacy callers retain locked validation.
        if(!token.matches("[0-9a-f]{64}"))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Invalid Attendance version precondition");
        Facts now=current(classId,scheduleId);
        if(!MessageDigest.isEqual(now.token().getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII)))
            throw new ApiException(HttpStatus.CONFLICT,
                "Academic lesson or teacher/CS assignment changed during Attendance write");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}

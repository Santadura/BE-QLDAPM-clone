package com.example.demo.service;

import com.example.demo.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Attendance must lock the exact lesson/teacher/CS rows used for validation
 * before any status mutation. Otherwise class rescheduling or CS cancellation
 * could invalidate the earlier checks while the same transaction is writing.
 */
class TeacherSessionAttendanceLockingTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAccess access=mock(SchedulingAccess.class);
    private final TeacherSessionAttendanceService service=
        new TeacherSessionAttendanceService(db,access);

    private void activeTeacherShift(LocalDate date) {
        when(access.requireRole("CS")).thenReturn(
            new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
        when(db.queryForList(startsWith("SELECT t.date"),
            eq("teach-1"),eq("class-1"))).thenReturn(List.of(Map.of(
                "date",Date.valueOf(date),
                "start_time",Time.valueOf("09:00:00"),
                "end_time",Time.valueOf("11:00:00"),
                "status","ASSIGNED",
                "attendance_status","ABSENT")));
    }

    @Test void authorizedCsHoldsAllFourBusinessLocksBeforeRecordingAttendance() {
        activeTeacherShift(LocalDate.of(2020,1,1));
        when(db.queryForList(startsWith("SELECT s.staff_schedule_id"),
            eq("teach-1"),eq("cs-001"))).thenReturn(List.of(
                Map.of("staff_schedule_id","cs-shift-1")));
        when(db.queryForMap(startsWith("SELECT a.attendance_id"),
            eq("teach-1"))).thenReturn(Map.of("id","attendance-1","status","PRESENT"));

        var result=service.mark("class-1","teach-1",
            new TeacherSessionAttendanceService.MarkRequest("PRESENT"));
        assertEquals("PRESENT",result.get("status"));
        var order=inOrder(db,access);
        order.verify(access).requireRole("CS");
        order.verify(db).queryForList(
            contains("FROM classes WHERE class_id=? FOR SHARE"),eq("class-1"));
        order.verify(db).queryForList(
            contains("FOR UPDATE OF t,lesson"),eq("teach-1"),eq("class-1"));
        order.verify(db).queryForList(
            contains("LIMIT 1 FOR SHARE OF s"),eq("teach-1"),eq("cs-001"));
        order.verify(db).update(startsWith("INSERT INTO teacher_session_attendance"),
            any(Object[].class));
        verify(access).audit(any(),eq("RECORD_TEACHER_ATTENDANCE_PRESENT"),
            eq("TEACHING_SCHEDULE"),eq("teach-1"));
    }

    @Test void cancelledCsSupportShiftCannotMarkAttendance() {
        activeTeacherShift(LocalDate.of(2020,1,1));
        when(db.queryForList(startsWith("SELECT s.staff_schedule_id"),
            eq("teach-1"),eq("cs-001"))).thenReturn(List.of());

        var error=assertThrows(ApiException.class,
            ()->service.mark("class-1","teach-1",
                new TeacherSessionAttendanceService.MarkRequest("PRESENT")));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatus());
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(access,never()).audit(any(),anyString(),anyString(),anyString());
    }

    @Test void teacherNotAssignedToPlannedLessonCannotMarkAttendance() {
        when(access.requireRole("CS")).thenReturn(
            new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
        when(db.queryForList(startsWith("SELECT t.date"),
            eq("teach-1"),eq("class-1"))).thenReturn(List.of());

        var error=assertThrows(ApiException.class,
            ()->service.mark("class-1","teach-1",
                new TeacherSessionAttendanceService.MarkRequest("PRESENT")));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatus());
        verify(db,never()).queryForList(startsWith("SELECT s.staff_schedule_id"),
            any(Object[].class));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void futureLessonStillCannotBeMarkedEvenWithValidCsSupport() {
        activeTeacherShift(LocalDate.of(2099,1,1));
        when(db.queryForList(startsWith("SELECT s.staff_schedule_id"),
            eq("teach-1"),eq("cs-001"))).thenReturn(List.of(
                Map.of("staff_schedule_id","cs-shift-1")));

        var error=assertThrows(ApiException.class,
            ()->service.mark("class-1","teach-1",
                new TeacherSessionAttendanceService.MarkRequest("PRESENT")));
        assertEquals(HttpStatus.CONFLICT,error.getStatus());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

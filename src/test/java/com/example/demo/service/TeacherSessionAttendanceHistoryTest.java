package com.example.demo.service;

import com.example.demo.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherSessionAttendanceHistoryTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final SchedulingAccess access = mock(SchedulingAccess.class);
    private final TeacherSessionAttendanceService service = new TeacherSessionAttendanceService(db, access);

    @Test
    void attendanceListIncludesRecordedCancelledTeachingAssignments() {
        when(access.requireRole("CS"))
            .thenReturn(new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
        when(db.queryForObject(anyString(),eq(Integer.class),
                eq("class-01"),eq("cs-001"),eq("cs-001"))).thenReturn(1);
        when(db.queryForList(anyString(),eq("cs-001"),eq("class-01")))
            .thenReturn(List.of());

        assertDoesNotThrow(() -> service.list("class-01"));
        verify(db).queryForList(
            argThat(sql -> sql.contains("t.status AS \"assignmentStatus\"") &&
                sql.contains("t.status='CANCELLED' AND a.attendance_id IS NOT NULL") &&
                sql.contains("lesson.status IN ('PLANNED','CANCELLED')") &&
                sql.contains("t.status='ASSIGNED' AND lesson.status='PLANNED'")),
            eq("cs-001"),eq("class-01"));
    }

    @Test
    void cancelledTeacherAttendanceCannotBeEditedThroughOldAssignmentId() {
        when(access.requireRole("CS"))
            .thenReturn(new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
        when(db.queryForList(anyString(),eq("old-teaching-id"),eq("class-01")))
            .thenReturn(List.of());
        assertThrows(ApiException.class, () -> service.mark(
            "class-01","old-teaching-id",
            new TeacherSessionAttendanceService.MarkRequest("LATE")));
        verify(db).queryForList(
            argThat(sql -> sql.contains("t.status='ASSIGNED'")),
            eq("old-teaching-id"),eq("class-01"));
        verify(db, never()).update(anyString(),any(Object[].class));
    }
}

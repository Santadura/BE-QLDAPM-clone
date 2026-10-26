package com.example.demo.service;

import com.example.demo.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClassLifecycleIntegrityServiceTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ClassLifecycleIntegrityService policy = new ClassLifecycleIntegrityService(db);

    @Test
    void readyClassCannotRunUntilCmAssignsCsForEveryPlannedLesson() {
        when(db.queryForObject(anyString(), eq(Integer.class), eq("class-01")))
            .thenReturn(2);
        ApiException error = assertThrows(ApiException.class,
            () -> policy.requireCsCoverageBeforeRunning("class-01"));
        assertTrue(error.getMessage().contains("2 planned lesson(s)"));
        verify(db).queryForObject(
            argThat(sql -> sql.contains("CLASS_SUPPORT") &&
                sql.contains("status='ASSIGNED'") &&
                sql.contains("cs.start_time=ss.start_time")),
            eq(Integer.class),eq("class-01"));
    }

    @Test
    void readyClassWithFullCsCoverageCanRun() {
        when(db.queryForObject(anyString(), eq(Integer.class), eq("class-01")))
            .thenReturn(0);
        assertDoesNotThrow(() -> policy.requireCsCoverageBeforeRunning("class-01"));
    }

    @Test
    void cannotCompleteClassIfTeacherAttendanceIsMissing() {
        when(db.queryForObject(anyString(),eq(Integer.class),eq("class-01"),
                any(LocalDate.class),any(LocalDate.class),any(LocalTime.class)))
            .thenReturn(3);
        ApiException error = assertThrows(ApiException.class,
            () -> policy.requireTeacherAttendanceBeforeCompleted("class-01"));
        assertTrue(error.getMessage().contains("3 teacher lesson(s)"));
        verify(db).queryForObject(
            argThat(sql -> sql.contains("teacher_session_attendance") &&
                sql.contains("ta.attendance_id IS NULL") &&
                sql.contains("ts.status='ASSIGNED'")),
            eq(Integer.class),eq("class-01"),
            any(LocalDate.class),any(LocalDate.class),any(LocalTime.class));
    }

    @Test
    void allTaughtLessonsMarkedCanComplete() {
        when(db.queryForObject(anyString(),eq(Integer.class),eq("class-01"),
                any(LocalDate.class),any(LocalDate.class),any(LocalTime.class)))
            .thenReturn(0);
        assertDoesNotThrow(() -> policy.requireTeacherAttendanceBeforeCompleted("class-01"));
    }
}

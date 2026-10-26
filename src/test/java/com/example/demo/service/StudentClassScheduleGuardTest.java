package com.example.demo.service;

import com.example.demo.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StudentClassScheduleGuardTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final StudentClassScheduleGuard guard = new StudentClassScheduleGuard(db);

    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private static final LocalTime START = LocalTime.of(18, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    @Test
    void overlapUsesStrictIntervalIntersection() {
        assertTrue(StudentClassScheduleGuard.timesOverlap(
            START, END, LocalTime.of(19,0), LocalTime.of(21,0)));
        assertTrue(StudentClassScheduleGuard.timesOverlap(
            START, END, LocalTime.of(17,0), LocalTime.of(19,0)));
        assertFalse(StudentClassScheduleGuard.timesOverlap(
            START, END, END, LocalTime.of(22,0)));
        assertFalse(StudentClassScheduleGuard.timesOverlap(
            START, END, LocalTime.of(16,0), START));
    }

    @Test
    void enrollmentConflictsAgainstExistingActiveRosterAndPlannedLessons() {
        when(db.queryForList(anyString(), eq("hv-001"), eq("class-b"))).thenReturn(
            List.of(Map.of(
                "otherClass", "IELTS-A",
                "date", DATE,
                "startTime", START,
                "endTime", END
            )));
        String message = guard.enrollmentConflict("hv-001", "class-b");
        assertNotNull(message);
        assertTrue(message.contains("IELTS-A"));
        assertTrue(message.contains("2026-11-03"));
        verify(db).queryForList(
            argThat(sql -> sql.contains("occupied.start_time<proposed.end_time") &&
                    sql.contains("member.status='ACTIVE'") &&
                    sql.contains("occupied.status='PLANNED'")),
            eq("hv-001"),eq("class-b"));
    }

    @Test
    void enrollmentWithoutOverlappingPlannedSessionsIsAllowed() {
        when(db.queryForList(anyString(), eq("hv-001"), eq("class-b")))
            .thenReturn(List.of());
        assertNull(guard.enrollmentConflict("hv-001", "class-b"));
    }

    @Test
    void changingATimetableCannotCauseAnotherClassConflict() {
        when(db.queryForList(anyString(), eq(DATE), eq(START), eq(END),
            eq(DATE), eq(END), eq(START), eq("class-b"))).thenReturn(
            List.of(Map.of("otherClass","IELTS-A","studentCode","HV001")));
        var ex = assertThrows(ApiException.class, () -> guard.validateTimetable(
            "class-b", List.of(new StudentClassScheduleGuard.Slot(DATE, START, END))));
        assertTrue(ex.getMessage().contains("STUDENT_SCHEDULE_CONFLICT"));
        assertTrue(ex.getMessage().contains("HV001"));
    }

    @Test
    void nonConflictingTimetableIsAllowedAndChecksAreNoOpsWithoutLessons() {
        assertDoesNotThrow(() -> guard.validateTimetable("class-b", List.of()));
        verifyNoInteractions(db);
        assertDoesNotThrow(() -> guard.validateTimetable(
            "class-b", List.of(new StudentClassScheduleGuard.Slot(DATE,START,END))));
    }

    @Test
    void classStudentLocksAreOrderedForConsistentConcurrentMutations() {
        guard.lockClassStudents("class-b");
        verify(db).queryForList(
            argThat(sql -> sql.contains("ORDER BY s.student_id FOR UPDATE OF s")),
            eq("class-b"));
    }
}

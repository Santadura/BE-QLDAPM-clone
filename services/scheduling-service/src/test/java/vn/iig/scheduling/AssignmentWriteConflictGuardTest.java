package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AssignmentWriteConflictGuardTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AssignmentWriteConflictGuard guard=new AssignmentWriteConflictGuard(db);
    private final LocalDate date=LocalDate.of(2027,2,11);
    private final LocalTime from=LocalTime.of(9,0),to=LocalTime.of(11,0);
    private final AssignmentWriteConflictGuard.Candidate staff=
        new AssignmentWriteConflictGuard.Candidate(
            "STAFF","cs-001",null,null,date,from,to,"lease-1");

    private void approvedLease() {
        when(db.queryForObject(contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("lease-1"),eq("cs-001"),eq(date),
            eq(from),eq(to))).thenReturn(1L);
    }

    @Test void rejectsExpiredOrMissingLeaseBeforeCheckingCommittedSchedules() {
        when(db.queryForObject(contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("lease-1"),eq("cs-001"),eq(date),
            eq(from),eq(to))).thenReturn(0L);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,
            ()->guard.requireUnoccupied(staff));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(db,never()).queryForObject(
            contains("FROM scheduling_assignment_write_shadow"),eq(Long.class),
            any(),any(),any(),any(),any(),any());
    }

    @Test void overlappingAlreadyCommittedShiftIsRejected() {
        approvedLease();
        when(db.queryForObject(contains("status='ASSIGNED' AND employee_id=?"),
            eq(Long.class),eq("cs-001"),eq(date),eq(to),eq(from),
            eq("STAFF"),eq(""))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->guard.requireUnoccupied(staff)).getStatusCode());
    }

    @Test void workerCanUseAdjacentSlotWithValidLease() {
        approvedLease();
        when(db.queryForObject(contains("status='ASSIGNED' AND employee_id=?"),
            eq(Long.class),eq("cs-001"),eq(date),eq(to),eq(from),
            eq("STAFF"),eq(""))).thenReturn(0L);
        assertDoesNotThrow(()->guard.requireUnoccupied(staff));
        verify(db).queryForObject(contains("start_time<? AND end_time>?"),
            eq(Long.class),eq("cs-001"),eq(date),eq(to),eq(from),
            eq("STAFF"),eq(""));
    }

    @Test void separateTeachersCannotClaimSameClassSession() {
        var teacher=new AssignmentWriteConflictGuard.Candidate(
            "TEACHING","teacher-001","class-01",null,date,from,to,"lease-t");
        when(db.queryForObject(contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("lease-t"),eq("teacher-001"),eq(date),
            eq(from),eq(to))).thenReturn(1L);
        when(db.queryForObject(contains("status='ASSIGNED' AND class_id=?"),
            eq(Long.class),eq("class-01"),eq(date),eq(to),eq(from),eq("")))
            .thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->guard.requireUnoccupied(teacher)).getStatusCode());
        verify(db).queryForList(contains("pg_advisory_xact_lock"),eq("assignment-class:class-01"));
    }

    @Test void invalidCandidateDoesNotAcquireLocks() {
        var bad=new AssignmentWriteConflictGuard.Candidate(
            "TEACHING","teacher-001","class-01",null,date,to,from,"lease-t");
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->guard.requireUnoccupied(bad)).getStatusCode());
        verifyNoInteractions(db);
    }
}

package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.scheduling.SchedulingModels.ReservationRequest;

/** Phase 8b: in-flight assignment conflicts are controlled by Scheduling DB. */
class AssignmentIntentReservationTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AvailabilityRepository repository=new AvailabilityRepository(db);
    private final LocalDate date=LocalDate.of(2026,12,8);

    private ReservationRequest request(LocalTime start,LocalTime end) {
        return new ReservationRequest("teacher-001",date,start,end,"Cơ sở chính");
    }

    @Test void overlappingActiveEmployeeIntentReturnsConflictBeforeAnyInsert() {
        var request=request(LocalTime.of(9,0),LocalTime.of(11,0));
        when(db.queryForObject(
            contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("teacher-001"),eq(date),
            eq(LocalTime.of(11,0)),eq(LocalTime.of(9,0))))
            .thenReturn(1L);
        ResponseStatusException ex=assertThrows(ResponseStatusException.class,
            ()->repository.assertNoConcurrentAssignmentIntent(request));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void emptyOrExpiredIntentDoesNotBlockNewAssignment() {
        var request=request(LocalTime.of(11,0),LocalTime.of(12,0));
        when(db.queryForObject(
            contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("teacher-001"),eq(date),
            eq(LocalTime.of(12,0)),eq(LocalTime.of(11,0))))
            .thenReturn(0L);
        assertDoesNotThrow(()->repository.assertNoConcurrentAssignmentIntent(request));
        verify(db).queryForObject(
            argThat((String sql)->sql.contains("i.start_time<?") &&
                sql.contains("i.end_time>?") &&
                sql.contains("lease.expires_at>NOW()")),
            eq(Long.class),eq("teacher-001"),eq(date),
            eq(LocalTime.of(12,0)),eq(LocalTime.of(11,0)));
    }

    @Test void approvedReservationPersistsAnIntentInTheSameTransaction() {
        var req=request(LocalTime.of(9,0),LocalTime.of(11,0));
        when(db.query(contains("SELECT availability_id FROM scheduling_availabilities"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
                any(),any(),any(),any(),any(),any(),any()))
            .thenReturn(java.util.List.of("approved-slot"));
        when(db.queryForObject(
            contains("FROM scheduling_assignment_intent_reservations"),
            eq(Long.class),eq("teacher-001"),eq(date),
            eq(LocalTime.of(11,0)),eq(LocalTime.of(9,0))))
            .thenReturn(0L);

        var lease=repository.reserve(req);
        assertTrue(lease.approved());
        assertNotNull(lease.reservationId());
        verify(db).update(startsWith("INSERT INTO scheduling_availability_reservations"),
            eq(lease.reservationId()),eq("approved-slot"),eq("teacher-001"));
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_intent_reservations"),
            eq(lease.reservationId()),eq("teacher-001"),eq(date),
            eq(LocalTime.of(9,0)),eq(LocalTime.of(11,0)));

        // The FK ON DELETE CASCADE removes this intent with its parent lease.
        repository.release(lease.reservationId());
        verify(db).update(startsWith("DELETE FROM scheduling_availability_reservations"),
            eq(lease.reservationId()));
    }

    @Test void aDifferentWorkerIsLockedAndCheckedByTheirOwnEmployeeId() {
        var request=new ReservationRequest("cs-001",date,
            LocalTime.of(9,0),LocalTime.of(11,0),null);
        repository.assertNoConcurrentAssignmentIntent(request);
        verify(db).queryForObject(
            contains("i.employee_id=?"),eq(Long.class),eq("cs-001"),
            eq(date),eq(LocalTime.of(11,0)),eq(LocalTime.of(9,0)));
    }
}

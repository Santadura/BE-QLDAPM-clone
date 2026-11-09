package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class AssignmentCommandLifecycleServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AssignmentCommandLifecycleService service=
        new AssignmentCommandLifecycleService(db);

    private void state(String current) {
        when(db.query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
            any(RowMapper.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(List.of(current));
    }

    @Test void stagedCommandCannotAdvanceWithoutSameTransactionDomainEvents() {
        state("STAGED");
        when(db.queryForObject(startsWith("SELECT count(*) FROM scheduling_assignment_domain_outbox"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.markLocallyApplied("cm001","ASSIGN_SUPPORT_BATCH","batch-01"))
                .getStatusCode());
        verify(db,never()).update(contains("SET state='PENDING_REFERENCE'"),
            any(Object[].class));
    }

    @Test void stagedCommandAdvancesOnlyWithDurablyEnqueuedEvents() {
        state("STAGED");
        when(db.queryForObject(startsWith("SELECT count(*) FROM scheduling_assignment_domain_outbox"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(2L);
        when(db.update(startsWith("UPDATE scheduling_assignment_command_inbox"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"))).thenReturn(1);
        service.markLocallyApplied("cm001","ASSIGN_SUPPORT_BATCH","batch-01");
        verify(db).update(contains("state='PENDING_REFERENCE'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"));
    }

    @Test void firstOfTwoEventsKeepsBatchPending() {
        state("PENDING_REFERENCE");
        when(db.queryForObject(contains("WHERE event_id=? AND payload_hash=?"),
            eq(Long.class),eq("event-1"),eq("a".repeat(64)),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(1L);
        when(db.queryForObject(contains("AND delivered_at IS NULL"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(1L);
        assertFalse(service.confirmReference("cm001","ASSIGN_SUPPORT_BATCH",
            "batch-01","event-1","a".repeat(64)));
        verify(db,never()).update(contains("state='COMMITTED'"),any(Object[].class));
    }

    @Test void lastEventConfirmsWholeCommandAndRepeatedAcknowledgementIsSafe() {
        state("PENDING_REFERENCE");
        when(db.queryForObject(contains("WHERE event_id=? AND payload_hash=?"),
            eq(Long.class),eq("event-last"),eq("b".repeat(64)),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(1L);
        when(db.queryForObject(contains("AND delivered_at IS NULL"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(0L);
        when(db.update(contains("state='COMMITTED'"),eq("cm001"),
            eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"))).thenReturn(1);
        assertTrue(service.confirmReference("cm001","ASSIGN_SUPPORT_BATCH",
            "batch-01","event-last","b".repeat(64)));
        verify(db).update(contains("delivered_at=NOW()"),
            eq("event-last"),eq("b".repeat(64)));
        verify(db).update(contains("state='COMMITTED'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"));
    }

    @Test void mismatchedEventDigestCannotAdvanceInbox() {
        state("PENDING_REFERENCE");
        when(db.queryForObject(contains("WHERE event_id=? AND payload_hash=?"),
            eq(Long.class),eq("unknown"),eq("c".repeat(64)),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.confirmReference("cm001","ASSIGN_SUPPORT_BATCH",
                    "batch-01","unknown","c".repeat(64))).getStatusCode());
        verify(db,never()).update(contains("delivered_at=NOW()"),any(Object[].class));
    }

    @Test void unresolvedEventRequiresManualReconciliationWithoutAutomaticCompensation() {
        state("PENDING_REFERENCE");
        when(db.queryForObject(contains("AND event_id=? AND delivered_at IS NULL"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),
            eq("batch-01"),eq("event-1"))).thenReturn(1L);
        when(db.update(contains("state='COMPENSATING'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"))).thenReturn(1);
        service.flagForReconciliation("cm001","ASSIGN_SUPPORT_BATCH",
            "batch-01","event-1");
        verify(db).update(contains("state='COMPENSATING'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"));
        verify(db,never()).update(contains("staff_schedules"),any(Object[].class));
    }

    @Test void deliveredEventCannotBeFlaggedForRollback() {
        state("PENDING_REFERENCE");
        when(db.queryForObject(contains("AND event_id=? AND delivered_at IS NULL"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),
            eq("batch-01"),eq("event-1"))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.flagForReconciliation("cm001","ASSIGN_SUPPORT_BATCH",
                    "batch-01","event-1")).getStatusCode());
        verify(db,never()).update(contains("state='COMPENSATING'"),
            any(Object[].class));
    }

    private AssignmentDomainOutboxRepository.Event unresolved() {
        return new AssignmentDomainOutboxRepository.Event(
            "event-1","cm001","ASSIGN_SUPPORT_BATCH","batch-01",
            "STAFF","shift-1",2,"UPSERT","{}","a".repeat(64),10);
    }

    private SchedulingCoreClient.DurableAssignmentReferenceReceipt proof() {
        return new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-1",2,"a".repeat(64));
    }

    @Test void verifiedFailedEventCanCompleteExhaustedCommandAtomically() {
        state("COMPENSATING");
        when(db.queryForObject(contains("AND source_type=?"),
            eq(Long.class),eq("event-1"),eq("a".repeat(64)),
            eq("STAFF"),eq("shift-1"),eq(2L),eq("cm001"),
            eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"))).thenReturn(1L);
        when(db.update(startsWith("UPDATE scheduling_assignment_domain_outbox SET delivered_at"),
            eq("event-1"),eq("a".repeat(64)))).thenReturn(1);
        when(db.queryForObject(contains("WHERE actor_username=? AND operation=?"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(0L);
        when(db.update(startsWith("UPDATE scheduling_assignment_command_inbox SET state='COMMITTED'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(1);
        assertTrue(service.reconcileVerified(unresolved(),proof()));
        verify(db).update(startsWith("UPDATE scheduling_assignment_command_inbox SET state='COMMITTED'"),
            eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"));
    }

    @Test void exactReceiptButMorePendingBatchEventsKeepsCompensating() {
        state("COMPENSATING");
        when(db.queryForObject(contains("AND source_type=?"),
            eq(Long.class),eq("event-1"),eq("a".repeat(64)),
            eq("STAFF"),eq("shift-1"),eq(2L),eq("cm001"),
            eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01"))).thenReturn(1L);
        when(db.update(startsWith("UPDATE scheduling_assignment_domain_outbox SET delivered_at"),
            eq("event-1"),eq("a".repeat(64)))).thenReturn(1);
        when(db.queryForObject(contains("WHERE actor_username=? AND operation=?"),
            eq(Long.class),eq("cm001"),eq("ASSIGN_SUPPORT_BATCH"),eq("batch-01")))
            .thenReturn(1L);
        assertFalse(service.reconcileVerified(unresolved(),proof()));
        verify(db,never()).update(contains("SET state='COMMITTED'"),
            any(Object[].class));
    }

    @Test void wrongCoreHashCannotBeUsedToRecoverACommand() {
        var bogus=new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-1",2,"f".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.reconcileVerified(unresolved(),bogus)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void unexhaustedCommandCannotUseManualReconciliationToSkipRelay() {
        state("PENDING_REFERENCE");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.reconcileVerified(unresolved(),proof())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void rejectedCommandCanNeverBeMarkedAsCommitted() {
        state("REJECTED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.confirmReference("cm001","ASSIGN_SUPPORT_BATCH",
                    "batch-01","event-1","d".repeat(64))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

package vn.iig.attendance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AttendanceEventRelayTest {
    private final AttendanceSourceClient core=mock(AttendanceSourceClient.class);
    private final AttendanceEventProjectionRepository store=
        mock(AttendanceEventProjectionRepository.class);
    private final AttendanceEventRelay relay=new AttendanceEventRelay(core,store,true);
    private final AttendanceSourceClient.Event event=
        new AttendanceSourceClient.Event(7,"teaching-001","UPSERT",
            "{\"teaching_schedule_id\":\"teaching-001\"}","a".repeat(64));

    @Test void localCommitPrecedesCoreAcknowledgement() {
        when(core.pending(100)).thenReturn(List.of(event),List.of());
        assertEquals(1,relay.drain());
        var order=inOrder(store,core);
        order.verify(core).pending(100);
        order.verify(store).apply(event);
        order.verify(core).acknowledge(event);
        order.verify(core).pending(100);
    }

    @Test void failedProjectionNeverAcknowledgesTheSource() {
        when(core.pending(100)).thenReturn(List.of(event));
        doThrow(new IllegalStateException("Local Attendance DB unavailable"))
            .when(store).apply(event);
        assertThrows(IllegalStateException.class,relay::drain);
        verify(core,never()).acknowledge(any());
    }

    @Test void lostAckIsRetriedWithSameImmutableSourceEvent() {
        when(core.pending(100)).thenReturn(List.of(event),List.of(event),List.of());
        doThrow(new IllegalStateException("Core lost response"))
            .doNothing().when(core).acknowledge(event);
        assertThrows(IllegalStateException.class,relay::drain);
        assertEquals(1,relay.drain());
        verify(store,times(2)).apply(event);
        verify(core,times(2)).acknowledge(event);
    }

    @Test void readBarrierRefusesToAssumeAFullBatchMeansCompletion() {
        when(core.pending(100)).thenReturn(java.util.List.of());
        when(core.pending(1)).thenReturn(java.util.List.of(event));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                relay::requireCaughtUp).getStatusCode());
    }

    @Test void readBarrierProvesSourceQueueIsReallyEmpty() {
        when(core.pending(100)).thenReturn(java.util.List.of());
        when(core.pending(1)).thenReturn(java.util.List.of());
        assertDoesNotThrow(relay::requireCaughtUp);
    }

    @Test void historicalBackfillAdvancesItsOwnCursorAfterEachCommit() {
        var older=new AttendanceSourceClient.Event(3,"teaching-001","UPSERT",
            "{\"teaching_schedule_id\":\"teaching-001\"}","b".repeat(64));
        when(store.historyCursor()).thenReturn(0L);
        when(core.history(0L,100)).thenReturn(List.of(older,event));
        assertTrue(relay.backfillHistory(5));
        var order=inOrder(store,core);
        order.verify(store).historyCursor();
        order.verify(core).history(0L,100);
        order.verify(store).apply(older);
        order.verify(store).advanceHistoryCursor(3L);
        order.verify(store).apply(event);
        order.verify(store).advanceHistoryCursor(7L);
        verify(core,never()).acknowledge(any());
    }

    @Test void historicalBackfillRejectsNonMonotonicSourcePages() {
        when(store.historyCursor()).thenReturn(6L);
        when(core.history(6L,100)).thenReturn(List.of(
            new AttendanceSourceClient.Event(6,"teaching-001","UPSERT",
                "{\"status\":\"PRESENT\"}","c".repeat(64))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            ()->relay.backfillHistory(2)).getStatusCode());
        verify(store,never()).apply(any());
    }

    @Test void legacyModeNeverRunsHistoricalImporter() {
        var disabled=new AttendanceEventRelay(core,store,false);
        assertFalse(disabled.backfillHistory(5));
        verifyNoInteractions(core,store);
    }

    @Test void relayRemainsDisabledInLegacyCoreOnlyDeployment() {
        new AttendanceEventRelay(core,store,false).tick();
        verifyNoInteractions(core,store);
    }
}

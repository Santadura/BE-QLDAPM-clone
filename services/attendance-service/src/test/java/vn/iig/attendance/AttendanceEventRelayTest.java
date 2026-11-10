package vn.iig.attendance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    @Test void relayRemainsDisabledInLegacyCoreOnlyDeployment() {
        new AttendanceEventRelay(core,store,false).tick();
        verifyNoInteractions(core,store);
    }
}

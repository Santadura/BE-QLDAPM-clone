package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionSourceRelayTest {
    private final AcademicSessionSourceClient core=mock(AcademicSessionSourceClient.class);
    private final AcademicSessionSourceRepository local=mock(AcademicSessionSourceRepository.class);
    private final AcademicSessionSourceRelay relay=
        new AcademicSessionSourceRelay(core,local,true);
    private static final AcademicSessionSourceClient.Event EVENT=
        new AcademicSessionSourceClient.Event(1,"lesson-1","class-1","UPSERT",2,
            "{\"class_session_id\":\"lesson-1\",\"class_id\":\"class-1\"}","a".repeat(64));

    @Test void importedTransactionMustCommitBeforeCoreAcknowledgement() {
        when(core.pending(100)).thenReturn(List.of(EVENT),List.of());
        assertEquals(1,relay.drain());
        var ordered=inOrder(local,core);
        ordered.verify(core).pending(100);
        ordered.verify(local).apply(EVENT,false);
        ordered.verify(core).acknowledge(EVENT);
    }
    @Test void failedLocalImportNeverAcknowledgesCore() {
        when(core.pending(100)).thenReturn(List.of(EVENT));
        doThrow(new IllegalStateException("postgres offline")).when(local).apply(EVENT,false);
        assertThrows(IllegalStateException.class,relay::drain);
        verify(core,never()).acknowledge(any());
    }
    @Test void historicalCursorAdvancesOnlyAfterLocalApply() {
        when(local.historyCursor()).thenReturn(0L);
        when(core.history(0,100)).thenReturn(List.of(EVENT));
        assertTrue(relay.backfill(5));
        var order=inOrder(core,local);
        order.verify(local).historyCursor();
        order.verify(core).history(0,100);
        order.verify(local).apply(EVENT,true);
    }
    @Test void nonMonotonicHistoryIsRejected() {
        when(local.historyCursor()).thenReturn(5L);
        when(core.history(5,100)).thenReturn(List.of(EVENT));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->relay.backfill(5)).getStatusCode());
        verify(local,never()).apply(any(),eq(true));
    }
    @Test void disabledRelayCannotRunBehindOperatorsBack() {
        var off=new AcademicSessionSourceRelay(core,local,false);
        assertEquals(0,off.drain());
        assertFalse(off.backfill(5));
        verifyNoInteractions(core,local);
    }
}

package vn.iig.payroll;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import vn.iig.payroll.PayrollAttendanceEvidenceClient.*;

class PayrollAttendanceReferenceRelayTest {
    private final PayrollAttendanceEvidenceClient client=mock(PayrollAttendanceEvidenceClient.class);
    private final PayrollAttendanceReferenceRepository repo=mock(PayrollAttendanceReferenceRepository.class);
    private final PayrollAttendanceReferenceRelay relay=
        new PayrollAttendanceReferenceRelay(client,repo,true);

    private static HistoryEvent e(long id) {
        return new HistoryEvent(id,"teach-1","UPSERT","PRESENT","a".repeat(64));
    }
    private static HistoryPage p(long revision,long count,List<HistoryEvent> events) {
        return new HistoryPage(revision,count,3,4,events);
    }
    private static Snapshot ready(long revision,long count) {
        return new Snapshot(revision,count,3,4,List.of());
    }

    @Test void replayCommitsOrderedPageThenVerifiesFullIndependentHistory() {
        var page=p(2,2,List.of(e(1),e(2)));
        when(repo.cursor()).thenReturn(0L,2L);
        when(repo.eventCount()).thenReturn(2L);
        when(client.history(0,100)).thenReturn(page);
        when(client.readiness()).thenReturn(ready(2,2));
        assertTrue(relay.poll(5));
        var order=inOrder(repo,client);
        order.verify(repo).cursor();
        order.verify(client).history(0,100);
        order.verify(repo).apply(page);
        order.verify(client).readiness();
        order.verify(repo).cursor();
        order.verify(repo).eventCount();
        order.verify(repo).divergentLatestRows();
    }

    @Test void sourceRevisionChangingBetweenPagesDoesNotAcknowledgeCompletion() {
        var hundred=new ArrayList<HistoryEvent>();
        for(int i=1;i<=100;i++)hundred.add(e(i));
        var first=p(100,100,hundred);
        var newer=p(101,101,List.of(e(101)));
        when(repo.cursor()).thenReturn(0L);
        when(client.history(0,100)).thenReturn(first);
        when(client.history(100,100)).thenReturn(newer);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->relay.poll(5)).getStatusCode());
        verify(repo).apply(first);
        verify(repo,never()).apply(newer);
        verify(client,never()).readiness();
    }

    @Test void replayRefusesOutOfOrderEventsBeforeDatabaseWrite() {
        when(repo.cursor()).thenReturn(0L);
        when(client.history(0,100)).thenReturn(p(5,2,List.of(e(3),e(2))));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->relay.poll(5)).getStatusCode());
        verify(repo,never()).apply(any());
    }

    @Test void completedLocalReplayCannotClaimReadyIfCountDiffers() {
        when(repo.cursor()).thenReturn(0L,2L);
        when(repo.eventCount()).thenReturn(1L);
        when(client.history(0,100)).thenReturn(p(2,2,List.of(e(1),e(2))));
        when(client.readiness()).thenReturn(ready(2,2));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->relay.poll(5)).getStatusCode());
    }

    @Test void locallyCorruptedLatestStatusBlocksTheStrongHistoryGate() {
        when(repo.cursor()).thenReturn(0L,1L);
        when(repo.eventCount()).thenReturn(1L);
        when(repo.divergentLatestRows()).thenReturn(1L);
        when(client.history(0,100)).thenReturn(p(1,1,List.of(e(1))));
        when(client.readiness()).thenReturn(ready(1,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->relay.poll(5)).getStatusCode());
    }

    @Test void disabledRelayNeverReadsRemoteOrWritesPayrollDatabase() {
        var disabled=new PayrollAttendanceReferenceRelay(client,repo,false);
        disabled.tick();
        assertFalse(disabled.poll(5));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,disabled::requireCaughtUp).getStatusCode());
        verifyNoInteractions(client,repo);
    }
}

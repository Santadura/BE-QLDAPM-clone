package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterSourceRelayTest {
    private final AcademicRosterSourceClient source=mock(AcademicRosterSourceClient.class);
    private final AcademicRosterSourceRepository target=mock(AcademicRosterSourceRepository.class);

    private AcademicRosterSourceClient.Event event(long id) {
        return new AcademicRosterSourceClient.Event(id,"STUDENT","student-1",
            "UPSERT",id,"{}","a".repeat(64));
    }

    @Test void relayIsOffUntilExplicitlyEnabled() {
        var relay=new AcademicRosterSourceRelay(source,target,false);
        assertEquals(0,relay.replay(10));
        relay.tick();
        verifyNoInteractions(source,target);
    }
    @Test void replayIsSequentialAndDoesNotSkipFailedLocalCommit() {
        var relay=new AcademicRosterSourceRelay(source,target,true);
        var first=event(4);
        var second=event(8);
        when(target.cursor()).thenReturn(2L);
        when(source.history(2,100)).thenReturn(List.of(first,second));
        assertEquals(2,relay.replay(2));
        var ordered=inOrder(target,source);
        ordered.verify(target).cursor();
        ordered.verify(source).history(2,100);
        ordered.verify(target).apply(first);
        ordered.verify(target).apply(second);
        verify(source,never()).history(8,100);
    }
    @Test void invalidEventOrderFailsBeforeAcademicCommit() {
        var relay=new AcademicRosterSourceRelay(source,target,true);
        when(target.cursor()).thenReturn(7L);
        when(source.history(7,100)).thenReturn(List.of(event(7)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->relay.replay(2)).getStatusCode());
        verify(target,never()).apply(any());
    }
    @Test void remoteFailureCannotAdvanceCursor() {
        var relay=new AcademicRosterSourceRelay(source,target,true);
        when(target.cursor()).thenReturn(12L);
        when(source.history(12,100)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertThrows(ResponseStatusException.class,()->relay.replay(10));
        verify(target,never()).apply(any());
    }
}

package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCutoverReadinessTest {
    AcademicSessionSourceClient core=mock(AcademicSessionSourceClient.class);
    AcademicSessionSourceRepository local=mock(AcademicSessionSourceRepository.class);
    AcademicSessionCutoverReadiness service=new AcademicSessionCutoverReadiness(core,local);
    static final AcademicSessionSourceClient.WriterFence FROZEN=
        new AcademicSessionSourceClient.WriterFence("QUIESCED",4,"QUIESCED",8,"QUIESCED",9);
    static final AcademicSessionSourceClient.Integrity CLEAN=
        new AcademicSessionSourceClient.Integrity(0,0,2,0,5,1,0);
    static final AcademicSessionSourceClient.Event UPSERT=
        new AcademicSessionSourceClient.Event(1,"lesson-1","class-1","UPSERT",1,
            "{\"id\":\"lesson-1\"}","a".repeat(64));
    static final AcademicSessionSourceClient.Event DELETE=
        new AcademicSessionSourceClient.Event(5,"lesson-1","class-1","DELETE",3,
            "{\"id\":\"lesson-1\"}","b".repeat(64));
    void setupReady() {
        when(core.writerFence()).thenReturn(FROZEN);
        when(core.integrity()).thenReturn(CLEAN);
        when(local.historyCursor()).thenReturn(5L);
        when(local.receiptCount()).thenReturn(2L);
        when(local.latestCount()).thenReturn(1L);
        when(local.latestDivergence()).thenReturn(0L);
        when(core.history(0,100)).thenReturn(List.of(UPSERT,DELETE));
        when(local.matchesEvent(UPSERT)).thenReturn(true);
        when(local.matchesEvent(DELETE)).thenReturn(true);
    }
    @Test void refusesLiveCoreWriterWithoutReadingReplica() {
        when(core.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence("CORE",1,"QUIESCED",2,"QUIESCED",3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(local);
        verify(core,never()).integrity();
    }
    @Test void validatesCompleteBackfillIncludingDelete() {
        setupReady();
        var proof=service.verify();
        assertEquals(2,proof.sourceEvents());
        assertEquals(5,proof.lastEventId());
        verify(local).matchesEvent(DELETE);
        verify(core,times(2)).writerFence();
    }
    @Test void unacknowledgedOrUntrackedCoreEventFailsClosed() {
        when(core.writerFence()).thenReturn(FROZEN);
        when(core.integrity()).thenReturn(
            new AcademicSessionSourceClient.Integrity(1,0,2,1,5,1,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(local);
    }
    @Test void missingImmutableTombstoneFailsClosed() {
        setupReady();
        when(local.matchesEvent(DELETE)).thenReturn(false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void changedLatestProjectionRejectsBeforeHistoryScan() {
        setupReady();
        when(local.latestDivergence()).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verify(core,never()).history(anyLong(),anyInt());
    }
    @Test void concurrentAbaGenerationChangeFailsClosed() {
        setupReady();
        when(core.writerFence()).thenReturn(FROZEN,
            new AcademicSessionSourceClient.WriterFence("QUIESCED",5,
                "QUIESCED",8,"QUIESCED",9));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void skippedIntermediateEventFailsCountParity() {
        setupReady();
        when(core.history(0,100)).thenReturn(List.of(UPSERT));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
}

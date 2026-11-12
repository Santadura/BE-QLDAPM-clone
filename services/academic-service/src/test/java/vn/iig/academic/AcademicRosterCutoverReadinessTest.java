package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCutoverReadinessTest {
    final AcademicRosterSourceClient core=mock(AcademicRosterSourceClient.class);
    final AcademicRosterReadiness parity=mock(AcademicRosterReadiness.class);
    final AcademicRosterCutoverReadiness service=
        new AcademicRosterCutoverReadiness(core,parity);

    final AcademicRosterSourceClient.WriterFence frozen=
        new AcademicRosterSourceClient.WriterFence("QUIESCED",2);
    final AcademicRosterReadiness.Evidence fullHistory=
        new AcademicRosterReadiness.Evidence(11,15,4,3,7);

    @Test void coreOwnedWritesRefuseCutoverWithoutScanningAnyPII() {
        when(core.writerFence()).thenReturn(
            new AcademicRosterSourceClient.WriterFence("CORE",1));
        var ex=assertThrows(ResponseStatusException.class,service::verify);
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verifyNoInteractions(parity);
    }
    @Test void quiescedAndStableGenerationAcceptsExactHistory() {
        when(core.writerFence()).thenReturn(frozen);
        when(parity.verify()).thenReturn(fullHistory);
        var proof=service.verify();
        assertEquals(2,proof.writerGeneration());
        assertEquals(fullHistory.sourceEvents(),proof.sourceEvents());
        verify(parity).verify();
        verify(core,times(2)).writerFence();
    }
    @Test void thawThenFreezeAbaCannotPassByMatchingWriterMode() {
        when(core.writerFence()).thenReturn(frozen,
            new AcademicRosterSourceClient.WriterFence("QUIESCED",4));
        when(parity.verify()).thenReturn(fullHistory);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void changingModeToCoreDuringHistoryScanCannotPass() {
        when(core.writerFence()).thenReturn(frozen,
            new AcademicRosterSourceClient.WriterFence("CORE",3));
        when(parity.verify()).thenReturn(fullHistory);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void incompleteSourceEvidenceNeverConfersWriteOwnership() {
        when(core.writerFence()).thenReturn(frozen);
        when(parity.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic source history not yet identical"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void missingCoreFenceFailsClosedBeforeScanning() {
        when(core.writerFence()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(parity);
    }
}

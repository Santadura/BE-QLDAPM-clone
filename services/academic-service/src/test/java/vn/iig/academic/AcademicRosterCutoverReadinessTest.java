package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCutoverReadinessTest {
    final AcademicRosterSourceClient core=mock(AcademicRosterSourceClient.class);
    final AcademicRosterReadiness parity=mock(AcademicRosterReadiness.class);
    final AcademicRosterClaimClient claims=mock(AcademicRosterClaimClient.class);
    final AcademicRosterCandidateDispositionRepository disposition=
        mock(AcademicRosterCandidateDispositionRepository.class);
    final AcademicRosterCutoverReadiness service=
        new AcademicRosterCutoverReadiness(core,parity,claims,disposition);

    final AcademicRosterSourceClient.WriterFence frozen=
        new AcademicRosterSourceClient.WriterFence("QUIESCED",2);
    final AcademicRosterReadiness.Evidence fullHistory=
        new AcademicRosterReadiness.Evidence(11,15,4,3,7);

    AcademicRosterCutoverReadinessTest() {
        when(claims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(0,2,4));
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(2,2,0,0));
    }

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
    @Test void openCoreRosterClaimRefusesCutoverEvenIfShadowRowsMatch() {
        when(core.writerFence()).thenReturn(frozen);
        when(claims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(1,1,3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(parity);
        verify(core,times(1)).writerFence();
    }
    @Test void claimAcquiredAndReleasedDuringSnapshotIsNotInvisible() {
        when(core.writerFence()).thenReturn(frozen);
        when(claims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(0,2,4),
            new AcademicRosterClaimClient.Summary(0,3,6));
        when(parity.verify()).thenReturn(fullHistory);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verify(parity).verify();
    }
    @Test void newOpenClaimAfterParityForcesFailClosed() {
        when(core.writerFence()).thenReturn(frozen);
        when(claims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(0,2,4),
            new AcademicRosterClaimClient.Summary(1,2,5));
        when(parity.verify()).thenReturn(fullHistory);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }

    @Test void releasedClaimWithUnappliedRehearsalNeverMeansCutoverReady() {
        when(core.writerFence()).thenReturn(frozen);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(2,1,1,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(parity);
    }
    @Test void interruptedCoreReleaseReceiptBlocksAcademicReadiness() {
        when(core.writerFence()).thenReturn(frozen);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(2,2,0,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(parity);
    }
    @Test void stagingAndDisposingCandidateDuringParityScanIsDetected() {
        when(core.writerFence()).thenReturn(frozen);
        when(parity.verify()).thenReturn(fullHistory);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(0,0,0,0),
            new AcademicRosterCandidateDispositionRepository.Summary(1,1,0,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }

    @Test void stagingAllowsPriorUnappliedRehearsalButProductionCutoverStillBlocks() {
        when(core.writerFence()).thenReturn(frozen);
        when(parity.verify()).thenReturn(fullHistory);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(1,0,1,0));
        var proof=service.verifyStaging();
        assertEquals(2,proof.writerGeneration());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void stagingRefusesUnacknowledgedCoreHandoffOrOpenClaim() {
        when(core.writerFence()).thenReturn(frozen);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(1,0,1,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verifyStaging).getStatusCode());
        verifyNoInteractions(parity);
    }
    @Test void stagingDetectsConcurrentCandidateLedgerMutation() {
        when(core.writerFence()).thenReturn(frozen);
        when(parity.verify()).thenReturn(fullHistory);
        when(disposition.summary()).thenReturn(
            new AcademicRosterCandidateDispositionRepository.Summary(1,0,1,0),
            new AcademicRosterCandidateDispositionRepository.Summary(2,0,2,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verifyStaging).getStatusCode());
    }

}

package vn.iig.academic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCrossDomainCutoverReadinessTest {
    final AcademicRosterSourceClient rcore=mock(AcademicRosterSourceClient.class);
    final AcademicSessionSourceClient score=mock(AcademicSessionSourceClient.class);
    final AcademicRosterCutoverReadiness roster=mock(AcademicRosterCutoverReadiness.class);
    final AcademicSessionCutoverReadiness sessions=mock(AcademicSessionCutoverReadiness.class);
    final AcademicRosterClaimClient rclaims=mock(AcademicRosterClaimClient.class);
    final AcademicCandidateClaimClient sclaims=mock(AcademicCandidateClaimClient.class);
    final AcademicCrossDomainCutoverReadiness service=
        new AcademicCrossDomainCutoverReadiness(rcore,score,roster,
            sessions,rclaims,sclaims);

    final AcademicRosterSourceClient.WriterFence rf=
        new AcademicRosterSourceClient.WriterFence("QUIESCED",2);
    final AcademicSessionSourceClient.WriterFence sf=
        new AcademicSessionSourceClient.WriterFence(
            "QUIESCED",2,"QUIESCED",2,"QUIESCED",2);
    final AcademicRosterClaimClient.Summary rc=
        new AcademicRosterClaimClient.Summary(0,2,4);
    final AcademicCandidateClaimClient.Summary sc=
        new AcademicCandidateClaimClient.Summary(0,1,2);
    final AcademicRosterCutoverReadiness.Evidence rp=
        new AcademicRosterCutoverReadiness.Evidence(2,11,15,4,3,7);
    final AcademicSessionCutoverReadiness.Evidence sp=
        new AcademicSessionCutoverReadiness.Evidence(20,30,12,4,2,2,2);

    @BeforeEach void consistent() {
        when(rcore.writerFence()).thenReturn(rf);
        when(score.writerFence()).thenReturn(sf);
        when(rclaims.summary()).thenReturn(rc);
        when(sclaims.summary()).thenReturn(sc);
        when(roster.verify()).thenReturn(rp);
        when(sessions.verify()).thenReturn(sp);
    }

    @Test void exactFourWriterAndTwoHistoryProofIsReadOnly() {
        var proof=service.verify();
        assertEquals(2,proof.rosterGeneration());
        assertEquals(2,proof.sessionGeneration());
        assertEquals(2,proof.attendanceGeneration());
        assertEquals(2,proof.schedulingGeneration());
        assertEquals(11,proof.rosterEvents());
        assertEquals(20,proof.sessionEvents());
        assertEquals(4,proof.rosterClaimAuditEvents());
        verify(roster).verify();
        verify(sessions).verify();
        verify(rcore,times(2)).writerFence();
        verify(score,times(2)).writerFence();
        verify(rclaims,times(2)).summary();
        verify(sclaims,times(2)).summary();
    }
    @Test void schedulingWriterStillCoreRefusesBeforeAnyHistoryScan() {
        when(score.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence(
                "QUIESCED",2,"QUIESCED",2,"CORE",1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(roster,sessions);
        verifyNoInteractions(rclaims,sclaims);
    }
    @Test void attendanceWriterStillCoreAlsoRefuses() {
        when(score.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence(
                "QUIESCED",2,"CORE",1,"QUIESCED",2));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(roster,sessions);
    }
    @Test void anyCoreRosterOpenClaimRefusesBothDomainScans() {
        when(rclaims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(1,1,3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(roster,sessions);
    }
    @Test void openSessionClaimAlsoRefusesBothDomainScans() {
        when(sclaims.summary()).thenReturn(
            new AcademicCandidateClaimClient.Summary(1,0,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(roster,sessions);
    }
    @Test void schedulingAbaDuringBothHistoryScansIsDenied() {
        when(score.writerFence()).thenReturn(sf,
            new AcademicSessionSourceClient.WriterFence(
                "QUIESCED",2,"QUIESCED",2,"QUIESCED",4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verify(roster).verify();
        verify(sessions).verify();
    }
    @Test void evenReleasedClaimDuringHistoryScanChangesLedgerCounter() {
        when(sclaims.summary()).thenReturn(sc,
            new AcademicCandidateClaimClient.Summary(0,2,4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
    }
    @Test void sessionParityFailurePreventsPositiveCrossDomainEvidence() {
        when(sessions.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session source history is incomplete"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verify(roster).verify();
    }
    @Test void coreFenceNetworkOutageIsFailClosed() {
        when(score.writerFence()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,service::verify).getStatusCode());
        verifyNoInteractions(roster,sessions);
    }
}

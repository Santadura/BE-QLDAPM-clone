package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeClaimHandoffRecoveryTest {
    private static final String EVENT="00000000-0000-0000-0000-000000000123";
    private static final String HASH="a".repeat(64);
    private static final String OUTBOX="b".repeat(64);
    private final AcademicNativeSessionRepository journal=
        mock(AcademicNativeSessionRepository.class);
    private final AcademicNativeCommandClaimClient core=
        mock(AcademicNativeCommandClaimClient.class);
    private final AcademicFullCutoverReadiness readiness=
        mock(AcademicFullCutoverReadiness.class);
    private final AcademicNativeClaimHandoffRecovery repair=
        new AcademicNativeClaimHandoffRecovery(journal,core,readiness);

    private AcademicNativeSessionRepository.PendingHandoff pending() {
        return new AcademicNativeSessionRepository.PendingHandoff(
            "admin","class-1","PLAN","key-1",HASH,EVENT,OUTBOX,4,5);
    }
    private AcademicNativeCommandClaimClient.Claim claim() {
        return new AcademicNativeCommandClaimClient.Claim(
            "admin","class-1","PLAN","key-1",HASH,4,5,6,7);
    }
    private void localEvidence() {
        when(journal.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenReturn(new AcademicNativeSessionRepository.CommittedEvidence(
                HASH,EVENT,OUTBOX,new ObjectMapper().createArrayNode()));
    }

    @Test void privateRecoveryMustBeOptedIn() {
        var flag=AcademicNativeClaimHandoffRecovery.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.academic.native-claim-recovery-enabled"},
            flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }

    @Test void committedLocalEventAndOriginalCoreOpenClaimRecoverExactlyOnce() {
        var p=pending();localEvidence();
        when(core.inspect("admin","PLAN","key-1"))
            .thenReturn(new AcademicNativeCommandClaimClient.Inspection(
                claim(),"OPEN",null,null));
        when(readiness.verifyStaging()).thenReturn(
            new AcademicFullCutoverReadiness.Evidence(
                5,4,6,7,0,0,0,0,0,0,null));
        repair.recover(p);
        var order=inOrder(journal,core);
        order.verify(journal).committedEvidence("admin","class-1","PLAN","key-1",HASH);
        order.verify(core).inspect("admin","PLAN","key-1");
        order.verify(core).release(argThat(x->x.claim().equals(claim()) &&
            EVENT.equals(x.eventId()) && OUTBOX.equals(x.outboxHash())));
        order.verify(journal).recordClaimHandoff(p);
        verify(readiness,times(2)).verifyStaging();
    }

    @Test void releasedCoreClaimMayRepairLostLocalAcknowledgementWithoutFrozenSource() {
        var p=pending();localEvidence();
        when(core.inspect("admin","PLAN","key-1"))
            .thenReturn(new AcademicNativeCommandClaimClient.Inspection(
                claim(),"RELEASED",EVENT,OUTBOX));
        repair.recover(p);
        verifyNoInteractions(readiness);
        verify(core,never()).release(any());
        verify(journal).recordClaimHandoff(p);
    }

    @Test void coreClaimEpochMismatchMustNeverReleaseOrRecordHandoff() {
        var p=pending();localEvidence();
        when(core.inspect("admin","PLAN","key-1"))
            .thenReturn(new AcademicNativeCommandClaimClient.Inspection(
                new AcademicNativeCommandClaimClient.Claim(
                    "admin","class-1","PLAN","key-1",HASH,4,99,6,7),
                "OPEN",null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repair.recover(p)).getStatusCode());
        verify(core,never()).release(any());
        verify(journal,never()).recordClaimHandoff(any());
    }

    @Test void absentOrDivergentCommittedLocalEvidenceKeepsCoreClaimOpen() {
        var p=pending();
        when(journal.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Native source has no committed immutable event"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repair.recover(p)).getStatusCode());
        verifyNoInteractions(core,readiness);
        verify(journal,never()).recordClaimHandoff(any());
    }

    @Test void crossServiceParityFailureCannotReleaseOpenCoreClaim() {
        var p=pending();localEvidence();
        when(core.inspect("admin","PLAN","key-1"))
            .thenReturn(new AcademicNativeCommandClaimClient.Inspection(
                claim(),"OPEN",null,null));
        when(readiness.verifyStaging()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Independent Payroll proof unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->repair.recover(p)).getStatusCode());
        verify(core,never()).release(any());
        verify(journal,never()).recordClaimHandoff(any());
    }

    @Test void releasedClaimWithWrongOriginalOutboxMustNotBeAcknowledged() {
        var p=pending();localEvidence();
        when(core.inspect("admin","PLAN","key-1"))
            .thenReturn(new AcademicNativeCommandClaimClient.Inspection(
                claim(),"RELEASED",EVENT,"c".repeat(64)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repair.recover(p)).getStatusCode());
        verify(journal,never()).recordClaimHandoff(any());
    }
}

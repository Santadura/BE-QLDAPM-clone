package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceNativeReceiptRecoveryTest {
    private final AttendanceNativeLedgerRepository ledger=
        mock(AttendanceNativeLedgerRepository.class);
    private final AttendanceNativeReferenceClient core=
        mock(AttendanceNativeReferenceClient.class);
    private final AttendanceNativeReceiptRecovery repair=
        new AttendanceNativeReceiptRecovery(ledger,core,new ObjectMapper());
    private final AttendanceNativeLedgerRepository.Pending one=
        new AttendanceNativeLedgerRepository.Pending(
            "00000000-0000-0000-0000-000000000001","cs001","class-1",
            "teach-1","request-1",1,"{}","a".repeat(64));

    @BeforeEach void allowNormalRetryUnlessQuarantined() {
        when(ledger.recoveryEligible(anyString())).thenReturn(true);
    }

    @Test void originalCommittedCoreReferenceFinalizesLocalLedgerOnce() {
        var reply=new AttendanceNativeReferenceClient.Receipt(
            one.eventId(),one.teachingScheduleId(),1,one.payloadHash(),
            "COMMITTED",new ObjectMapper().createObjectNode()
                .put("teachingScheduleId","teach-1"));
        when(core.reference(one)).thenReturn(reply);
        assertTrue(repair.repair(one));
        verify(ledger).confirm(eq(one),argThat(r->
            one.eventId().equals(r.path("eventId").asText()) &&
            one.payloadHash().equals(r.path("payloadHash").asText())));
    }

    @Test void uncertainCoreReceiptCannotBeConvertedIntoBusinessSuccess() {
        when(core.reference(one)).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Core timeout after unknown commit"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->repair.repair(one)).getStatusCode());
        verify(ledger,never()).confirm(any(),any());
    }

    @Test void oneFailedEventDoesNotStarveNextDurablyCommittedReceipt() {
        var other=new AttendanceNativeLedgerRepository.Pending(
            "00000000-0000-0000-0000-000000000002","cs002","class-2",
            "teach-2","request-2",1,"{}","b".repeat(64));
        when(ledger.pending(25)).thenReturn(List.of(one,other));
        when(core.reference(one)).thenThrow(new ResponseStatusException(
            HttpStatus.CONFLICT,"claim invalid"));
        when(core.reference(other)).thenReturn(
            new AttendanceNativeReferenceClient.Receipt(other.eventId(),
                other.teachingScheduleId(),1,other.payloadHash(),"COMMITTED",
                new ObjectMapper().createObjectNode().put("teachingScheduleId","teach-2")));
        repair.tick();
        verify(ledger,never()).confirm(eq(one),any());
        verify(ledger).recordRecoveryFailure(eq(one),any(ResponseStatusException.class));
        verify(ledger).confirm(eq(other),any());
    }

    @Test void quarantinedOrBackoffOrphanCannotBypassRetryBudget() {
        var claim=orphan("request-1");
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(one.eventId(),"PRESENT",1,false));
        when(ledger.event(one.eventId())).thenReturn(one);
        when(ledger.recoveryEligible(one.eventId())).thenReturn(false);
        assertFalse(repair.recoverClaim(claim));
        verify(core,never()).reference(any());
    }

    @Test void reconcilerRequiresOptInAndNeverStartsByDefault() {
        var annotation=AttendanceNativeReceiptRecovery.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation);
        assertArrayEquals(new String[]{"iig.attendance.native-recovery-enabled"},
            annotation.name());
        assertEquals("true",annotation.havingValue());
        assertFalse(annotation.matchIfMissing());
    }
    private AttendanceNativeReferenceClient.Claim orphan(String key) {
        return new AttendanceNativeReferenceClient.Claim(
            "11111111-1111-1111-1111-111111111111","cs001","employee-1",
            "class-1","teach-1",key,
            AttendanceCommandService.fingerprint("class-1","teach-1","PRESENT"),
            "PRESENT","a".repeat(64),2,null,null,"ATTENDANCE","OPEN");
    }

    @Test void claimSurvivesCrashBeforeLocalTransactionAndReconstructsExactEvent() {
        var claim=orphan("request-1");
        when(core.recoverable(25)).thenReturn(List.of(claim));
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(one.eventId(),"PRESENT",1,false));
        when(ledger.event(one.eventId())).thenReturn(one);
        when(core.reference(one)).thenReturn(
            new AttendanceNativeReferenceClient.Receipt(one.eventId(),
                one.teachingScheduleId(),1,one.payloadHash(),"COMMITTED",
                new ObjectMapper().createObjectNode().put("teachingScheduleId","teach-1")));
        repair.tick();
        verify(ledger).stage(argThat(a->
            claim.coreClaimId().equals(a.coreClaimId()) &&
            claim.requestKey().equals(a.requestKey()) &&
            claim.requestHash().equals(a.requestHash()) &&
            claim.writerGeneration()==a.writerGeneration()));
        verify(ledger).confirm(eq(one),any());
    }

    @Test void orphanClaimWithLostCoreReplyKeepsLocalOutboxPending() {
        var claim=orphan("request-1");
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(one.eventId(),"PRESENT",1,false));
        when(ledger.event(one.eventId())).thenReturn(one);
        when(core.reference(one)).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Lost acknowledgement"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->repair.recoverClaim(claim)).getStatusCode());
        verify(ledger,never()).confirm(any(),any());
    }

    @Test void revokedOrIncompleteCoreClaimNeverWritesAttendance() {
        var claim=new AttendanceNativeReferenceClient.Claim(
            "11111111-1111-1111-1111-111111111111","cs001","employee-1",
            "class-1","teach-1","request-1","a".repeat(64),
            "PRESENT","a".repeat(64),2,null,null,"ATTENDANCE","CONFIRMED");
        assertThrows(IllegalArgumentException.class,()->repair.recoverClaim(claim));
        verifyNoInteractions(ledger);
    }

    @Test void localCommitCanSurviveCrashAndRepeatedCoreOrphanScan() {
        var claim=orphan("request-1");
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(one.eventId(),"PRESENT",1,false));
        when(ledger.event(one.eventId())).thenReturn(one);
        when(core.reference(one)).thenReturn(
            new AttendanceNativeReferenceClient.Receipt(one.eventId(),
                one.teachingScheduleId(),1,one.payloadHash(),"COMMITTED",
                new ObjectMapper().createObjectNode().put("teachingScheduleId","teach-1")));
        assertTrue(repair.recoverClaim(claim));
        assertTrue(repair.recoverClaim(claim));
        verify(ledger,times(2)).stage(any());
        verify(ledger,times(2)).event(one.eventId());
        verify(ledger,times(2)).confirm(eq(one),any());
    }

}

package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
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
        verify(ledger).confirm(eq(other),any());
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
}

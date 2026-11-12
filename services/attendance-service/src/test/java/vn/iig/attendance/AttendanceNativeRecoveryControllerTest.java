package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceNativeRecoveryControllerTest {
    private static final String EVENT="00000000-0000-0000-0000-000000000001";
    private final AttendanceCoreClient core=mock(AttendanceCoreClient.class);
    private final AttendanceNativeLedgerRepository ledger=
        mock(AttendanceNativeLedgerRepository.class);
    private final AttendanceNativeReceiptRecovery recovery=
        mock(AttendanceNativeReceiptRecovery.class);
    private final AttendanceNativeRecoveryController controller=
        new AttendanceNativeRecoveryController(core,ledger,recovery,"private-key");

    @Test void disabledByDefaultAndOnlyEnabledWithNativeRecovery() {
        var flag=AttendanceNativeRecoveryController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.attendance.native-recovery-enabled"},
            flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }

    @Test void wrongPrivateKeyNeverReadsIdentityOrRecoveryState() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.status("wrong","Bearer admin")).getStatusCode());
        verifyNoInteractions(core,ledger,recovery);
    }

    @Test void liveNonAdminCannotInspectOrForceConfirmation() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("private-key","Bearer cs",EVENT)).getStatusCode());
        verifyNoInteractions(ledger,recovery);
    }

    @Test void noOperatorReviewCannotInvokeReferenceAgain() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("private-key","Bearer admin",EVENT)).getStatusCode());
        verify(ledger).requiresOperatorReview(EVENT);
        verify(ledger,never()).event(anyString());
        verifyNoInteractions(recovery);
    }

    @Test void quarantinedEventCanOnlyFinishFromOriginalVerifiedReference() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        when(ledger.requiresOperatorReview(EVENT)).thenReturn(true);
        var immutable=new AttendanceNativeLedgerRepository.Pending(
            EVENT,"cs001","class-1","teach-1","key-1",1,
            "{}","a".repeat(64));
        when(ledger.event(EVENT)).thenReturn(immutable);
        assertEquals("VERIFIED_COMMITTED",
            controller.verify("private-key","Bearer admin",EVENT).state());
        verify(recovery).repair(same(immutable));
        verify(ledger,never()).stage(any());
    }

    @Test void uncertainCoreReceiptStillFailsClosedForAdmin() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        when(ledger.requiresOperatorReview(EVENT)).thenReturn(true);
        var immutable=new AttendanceNativeLedgerRepository.Pending(
            EVENT,"cs001","class-1","teach-1","key-1",1,
            "{}","a".repeat(64));
        when(ledger.event(EVENT)).thenReturn(immutable);
        when(recovery.repair(immutable)).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("private-key","Bearer admin",EVENT))
            .getStatusCode());
    }
}

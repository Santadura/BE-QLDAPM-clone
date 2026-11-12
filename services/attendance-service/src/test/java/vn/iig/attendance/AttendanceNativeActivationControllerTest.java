package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceNativeActivationControllerTest {
    private final AttendanceCoreClient core=mock(AttendanceCoreClient.class);
    private final AttendanceSourceClient source=mock(AttendanceSourceClient.class);
    private final AttendanceCutoverVerifier verifier=mock(AttendanceCutoverVerifier.class);
    private final AttendanceNativeLedgerRepository ledger=
        mock(AttendanceNativeLedgerRepository.class);
    private final AttendanceNativeActivationController activation=
        new AttendanceNativeActivationController(core,source,verifier,ledger,
            new ObjectMapper(),"private-key","http://academic:8099","academic-key");

    @Test void privateAdminKeyIsRequiredBeforeAnyBusinessOrProofRead() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->activation.activate(null,"Bearer admin")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->activation.activate("wrong","Bearer admin")).getStatusCode());
        verifyNoInteractions(core,source,verifier,ledger);
    }

    @Test void roleRevocationStopsBeforeCoreWriterMutationOrLocalSeeding() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->activation.activate("private-key","Bearer cs")).getStatusCode());
        verifyNoInteractions(source,verifier,ledger);
    }

    @Test void frozenSourceIsRequiredBeforeCandidateIsEvenRead() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        when(source.writerFence()).thenReturn(
            new AttendanceSourceClient.WriterFence("CORE",3,"CORE",4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->activation.activate("private-key","Bearer admin")).getStatusCode());
        verifyNoInteractions(verifier,ledger);
    }

    @Test void incompleteIndependentHistoryNeverActivatesSecondWriter() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        when(source.writerFence()).thenReturn(
            new AttendanceSourceClient.WriterFence("QUIESCED",4,"QUIESCED",5));
        when(verifier.verifyFrozen()).thenReturn(
            new AttendanceCutoverVerifier.Report(false,"MISMATCH",3,2,1,
                java.util.List.of("MISSING_ATTENDANCE_EVENT")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->activation.activate("private-key","Bearer admin")).getStatusCode());
        verifyNoInteractions(ledger);
    }

    @Test void manualActivationIsAlwaysFeatureFlaggedOffByDefault() {
        var flag=AttendanceNativeActivationController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.attendance.native-admission-enabled"},
            flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }
}

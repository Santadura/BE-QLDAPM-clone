package vn.iig.attendance;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceCutoverControllerTest {
    private final AttendanceCutoverVerifier verifier=mock(AttendanceCutoverVerifier.class);
    private final AttendanceCutoverController controller=
        new AttendanceCutoverController(verifier,"private-attendance-key");

    @Test void privateVerificationRequiresServiceKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify("wrong")).getStatusCode());
        verifyNoInteractions(verifier);
    }

    @Test void onlyExactFullMatchReturnsOk() {
        when(verifier.verify()).thenReturn(new AttendanceCutoverVerifier.Report(
            true,"READY",1,1,0,List.of()));
        var result=controller.verify("private-attendance-key");
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertTrue(result.getBody().ready());
    }

    @Test void frozenPreflightAlsoRequiresPrivateKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.verifyFrozen(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.verifyFrozen("wrong")).getStatusCode());
        verifyNoInteractions(verifier);
    }

    @Test void onlyStableFrozenProofCanReturnOkFromFrozenEndpoint() {
        when(verifier.verifyFrozen()).thenReturn(new AttendanceCutoverVerifier.Report(
            true,"READY",2,2,0,List.of()));
        var result=controller.verifyFrozen("private-attendance-key");
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertTrue(result.getBody().ready());
    }

    @Test void unfrozenPreflightNeverClaimsReady() {
        when(verifier.verifyFrozen()).thenReturn(new AttendanceCutoverVerifier.Report(
            false,"NOT_FROZEN",0,0,1,List.of("CORE_WRITERS_NOT_QUIESCED")));
        var result=controller.verifyFrozen("private-attendance-key");
        assertEquals(HttpStatus.CONFLICT,result.getStatusCode());
    }

    @Test void mismatchReturnsConflictNotMisleadingSuccess() {
        when(verifier.verify()).thenReturn(new AttendanceCutoverVerifier.Report(
            false,"MISMATCH",1,1,1,List.of("teach-1")));
        var result=controller.verify("private-attendance-key");
        assertEquals(HttpStatus.CONFLICT,result.getStatusCode());
        assertFalse(result.getBody().ready());
    }
}

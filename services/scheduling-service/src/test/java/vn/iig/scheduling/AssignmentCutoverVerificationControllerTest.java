package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssignmentCutoverVerificationControllerTest {
    private final AssignmentCutoverVerificationService verifier=
        mock(AssignmentCutoverVerificationService.class);
    private final AssignmentCutoverVerificationController controller=
        new AssignmentCutoverVerificationController(verifier,"private-scheduling-key");

    @Test void privateCutoverGateNeverRunsForMissingOrWrongServiceKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->controller.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->controller.verify("wrong")).getStatusCode());
        verifyNoInteractions(verifier);
    }

    @Test void refusesWriteCutoverWhenProjectionDrifts() {
        var failed=new AssignmentCutoverVerificationService.Report(
            false,"PROJECTION_DRIFT",2,1,1,2,1,0,2,2,12,List.of("STAFF:s-1"));
        when(verifier.verify()).thenReturn(failed);
        var reply=controller.verify("private-scheduling-key");
        assertEquals(HttpStatus.CONFLICT,reply.getStatusCode());
        assertFalse(reply.getBody().ready());
    }

    @Test void reports200OnlyForFreshCompleteManifest() {
        var ready=new AssignmentCutoverVerificationService.Report(
            true,"READY",2,2,2,2,0,0,2,2,12,List.of());
        when(verifier.verify()).thenReturn(ready);
        var reply=controller.verify("private-scheduling-key");
        assertEquals(HttpStatus.OK,reply.getStatusCode());
        assertEquals("READY",reply.getBody().state());
    }
}

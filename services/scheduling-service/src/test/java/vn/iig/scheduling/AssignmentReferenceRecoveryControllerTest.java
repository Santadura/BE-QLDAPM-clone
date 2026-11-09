package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssignmentReferenceRecoveryControllerTest {
    private final AssignmentReferenceRecoveryService recovery=
        mock(AssignmentReferenceRecoveryService.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentReferenceRecoveryController controller=
        new AssignmentReferenceRecoveryController(recovery,core,"private-recovery-key");

    @Test void internalKeyMustBeValidatedBeforeIdentityLookup() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.verify(null,"Bearer sample","event-2")).getStatusCode());
        verifyNoInteractions(core,recovery);
    }

    @Test void onlyCurrentAdministratorMayReconcileExhaustedCommands() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.verify("private-recovery-key",
                    "Bearer sample","event-2")).getStatusCode());
        verifyNoInteractions(recovery);
    }

    @Test void authorizedAdminVerifiesExactReceiptInsteadOfForcingCommit() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer(null,"admin","ADMIN"));
        var result=new AssignmentReferenceRecoveryService.Recovery(
            "event-2","COMMITTED",true);
        when(recovery.verifyAndRecover("event-2")).thenReturn(result);
        assertEquals(result,controller.verify(
            "private-recovery-key","Bearer sample","event-2"));
        verify(recovery).verifyAndRecover("event-2");
    }
}

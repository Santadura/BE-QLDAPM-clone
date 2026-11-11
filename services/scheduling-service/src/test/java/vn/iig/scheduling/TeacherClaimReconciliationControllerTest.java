package vn.iig.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherClaimReconciliationControllerTest {
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherClaimReconciliationService svc=mock(TeacherClaimReconciliationService.class);
    private final TeacherClaimReconciliationController controller=
        new TeacherClaimReconciliationController(svc,core,"private-key");

    @Test void serviceKeyRejectedBeforeUserLookup() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.diagnose("bad","Bearer admin","tc001","cmd-1")).getStatusCode());
        verifyNoInteractions(core,svc);
    }
    @Test void onlyLiveAdminMayDiagnoseDistributedClaimState() {
        when(core.currentViewer("Bearer tc")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.diagnose("private-key","Bearer tc","tc001","cmd-1"))
            .getStatusCode());
        verifyNoInteractions(svc);
    }
    private static final String REASON="No Scheduling event exists after the aborted TC command";

    @Test void privateAbandonRejectsMissingKeyBeforeAnyAdminLookup() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.abandon(null,"Bearer admin",
                new TeacherClaimReconciliationController.AbandonRequest(
                    "tc001","orphan-1",REASON))).getStatusCode());
        verifyNoInteractions(core,svc);
    }

    @Test void tcCannotAbandonEvenItsOwnOrphanClaim() {
        when(core.currentViewer("Bearer tc")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.abandon("private-key","Bearer tc",
                new TeacherClaimReconciliationController.AbandonRequest(
                    "tc001","orphan-1",REASON))).getStatusCode());
        verifyNoInteractions(svc);
    }
    @Test void adminMustRejectSchedulingBeforeRequestingCoreRevocation() {
        when(core.currentViewer("Bearer admin")).thenReturn(
            new SchedulingCoreClient.Viewer(null,"admin","ADMIN"));
        when(svc.abandonLocally("tc001","orphan-1","admin",REASON)).thenReturn(
            new TeacherClaimReconciliationService.LocalAbandonReceipt(
                "tc001","orphan-1","REJECTED","admin",REASON));
        when(core.revokeOrphanTeacherClaim("tc001","orphan-1",REASON,
            "Bearer admin")).thenReturn(new SchedulingCoreClient.TeacherClaimRevocationReceipt(
                "tc001","orphan-1","REVOKED"));
        var result=controller.abandon("private-key","Bearer admin",
            new TeacherClaimReconciliationController.AbandonRequest(
                "tc001","orphan-1",REASON));
        assertEquals("REVOKED",result.coreClaimState());
        var order=inOrder(svc,core);
        order.verify(svc).abandonLocally("tc001","orphan-1","admin",REASON);
        order.verify(core).revokeOrphanTeacherClaim(
            "tc001","orphan-1",REASON,"Bearer admin");
    }

    @Test void authorizedAdminIsNotPermittedToModifyClaimOrReceipt() {
        when(core.currentViewer("Bearer admin")).thenReturn(
            new SchedulingCoreClient.Viewer(null,"admin","ADMIN"));
        controller.diagnose("private-key","Bearer admin","tc001","cmd-1");
        verify(svc).diagnose("tc001","cmd-1");
        verifyNoMoreInteractions(svc);
    }
}

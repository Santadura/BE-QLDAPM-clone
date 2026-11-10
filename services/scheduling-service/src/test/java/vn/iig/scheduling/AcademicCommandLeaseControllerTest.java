package vn.iig.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCommandLeaseControllerTest {
    private final AcademicCommandLeaseService svc=mock(AcademicCommandLeaseService.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AcademicCommandLeaseController controller=
        new AcademicCommandLeaseController(svc,core,"private-key");

    @Test void rejectsMissingServiceKeyWithoutQueryingIdentityOrCommands() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.acquire(null,"Bearer tc","command-1")).getStatusCode());
        verifyNoInteractions(svc,core);
    }

    @Test void revokedOrWrongRoleCannotAcquireAcademicReservation() {
        when(core.currentViewer("Bearer teacher")).thenReturn(
            new SchedulingCoreClient.Viewer("emp-1","teacher001","TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.acquire("private-key","Bearer teacher","command-1"))
            .getStatusCode());
        verifyNoInteractions(svc);
    }

    @Test void liveTcCanRenewOnlyItsOwnStagedAcademicLease() {
        when(core.currentViewer("Bearer tc")).thenReturn(
            new SchedulingCoreClient.Viewer("emp-tc-1","tc001","TC"));
        controller.renew("private-key","Bearer tc","command-1");
        verify(svc).renew("tc001","command-1");
    }
}

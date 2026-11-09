package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StandaloneStaffAssignmentExecutorControllerTest {
    private final StandaloneStaffAssignmentExecutor executor=
        mock(StandaloneStaffAssignmentExecutor.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final StandaloneStaffAssignmentExecutorController controller=
        new StandaloneStaffAssignmentExecutorController(
            executor,core,"private-scheduling-key");

    @Test void serviceKeyIsValidatedBeforeBearerOrDatabase() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.execute("wrong","Bearer sample","cmd-1")).getStatusCode());
        verifyNoInteractions(core,executor);
    }

    @Test void teacherAndInactiveProfilesCannotExecuteSchedulingWrites() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.execute("private-scheduling-key",
                    "Bearer sample","cmd-1")).getStatusCode());
        verifyNoInteractions(executor);
    }

    @Test void authorizedCmCanExecutePreviouslyStagedStandaloneCommand() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        var staged=new StandaloneStaffAssignmentExecutor.Result(
            "shift-1","PENDING_REFERENCE",1);
        when(executor.execute("cm001","cm-1","CM","cmd-1"))
            .thenReturn(staged);
        assertEquals(staged,controller.execute(
            "private-scheduling-key","Bearer sample","cmd-1"));
        verify(executor).execute("cm001","cm-1","CM","cmd-1");
    }
}

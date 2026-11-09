package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class StandaloneStaffAssignmentExecutorControllerTest {
    private final StandaloneStaffAssignmentExecutor executor=
        mock(StandaloneStaffAssignmentExecutor.class);
    private final StandaloneStaffCancellationExecutor cancellation=
        mock(StandaloneStaffCancellationExecutor.class);
    private final StandaloneStaffEditExecutor editing=
        mock(StandaloneStaffEditExecutor.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final StandaloneStaffAssignmentExecutorController controller=
        new StandaloneStaffAssignmentExecutorController(
            executor,cancellation,editing,core,"private-scheduling-key");

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

    @Test void privateCancellationRequiresCurrentCmAndServiceKey() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.cancel("wrong","Bearer sample","cancel-1"))
                .getStatusCode());
        verifyNoInteractions(core,executor,cancellation);
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.cancel("private-scheduling-key","Bearer sample","cancel-1"))
                .getStatusCode());
        verifyNoInteractions(cancellation);
    }

    @Test void authorizedCmCanCancelPreviouslyStagedStandaloneShift() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        var result=new StandaloneStaffAssignmentExecutor.Result(
            "shift-1","PENDING_REFERENCE",2);
        when(cancellation.cancel("cm001","cm-1","CM","cancel-1")).thenReturn(result);
        assertEquals(result,controller.cancel(
            "private-scheduling-key","Bearer sample","cancel-1"));
        verify(cancellation).cancel("cm001","cm-1","CM","cancel-1");
    }

    @Test void privateEditingRejectsMissingKeyAndFormerManagerRole() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.edit("wrong","Bearer sample","edit-1"))
                .getStatusCode());
        verifyNoInteractions(core,editing);
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.edit("private-scheduling-key",
                    "Bearer sample","edit-1")).getStatusCode());
        verifyNoInteractions(editing);
    }

    @Test void currentCmCanExecutePreviouslyStagedStandaloneEdit() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        var result=new StandaloneStaffAssignmentExecutor.Result(
            "shift-1","PENDING_REFERENCE",2);
        when(editing.edit("cm001","cm-1","CM","edit-1")).thenReturn(result);
        assertEquals(result,controller.edit(
            "private-scheduling-key","Bearer sample","edit-1"));
        verify(editing).edit("cm001","cm-1","CM","edit-1");
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

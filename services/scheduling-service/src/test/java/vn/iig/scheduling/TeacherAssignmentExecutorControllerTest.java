package vn.iig.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAssignmentExecutorControllerTest {
    private final TeacherAssignmentExecutor executor=mock(TeacherAssignmentExecutor.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherAssignmentExecutorController controller=
        new TeacherAssignmentExecutorController(executor,core,"private-scheduling-key");

    @Test void serviceKeyIsMandatoryBeforeLookingUpTcIdentity() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.execute(null,"Bearer tc","cmd-1")).getStatusCode());
        verifyNoInteractions(core,executor);
    }

    @Test void expiredTeacherBearerCannotWriteAnIndependentTeacherAssignment() {
        when(core.currentViewer("Bearer teacher")).thenReturn(
            new SchedulingCoreClient.Viewer("teacher-1","teacher001","TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.execute("private-scheduling-key",
                "Bearer teacher","cmd-1")).getStatusCode());
        verifyNoInteractions(executor);
    }

    @Test void liveTcCommandIsBoundToTheCurrentCoreEmployeeIdentity() {
        when(core.currentViewer("Bearer tc")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        controller.execute("private-scheduling-key","Bearer tc","cmd-1");
        verify(executor).execute("tc001","tc-1","TC","cmd-1");
    }
}

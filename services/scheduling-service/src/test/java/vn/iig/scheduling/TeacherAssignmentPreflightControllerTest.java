package vn.iig.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAssignmentPreflightControllerTest {
    private final TeacherAssignmentPreflightService service=
        mock(TeacherAssignmentPreflightService.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherAssignmentPreflightController controller=
        new TeacherAssignmentPreflightController(service,core,"private-scheduling-key");

    @Test void privateServiceKeyIsRequiredBeforeAnyIdentityLookup() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.prepare(null,"Bearer tc","request-1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.prepare("wrong","Bearer tc","request-1")).getStatusCode());
        verifyNoInteractions(core,service);
    }

    @Test void teacherOrNonTcCannotPrepareOthersAssignments() {
        when(core.currentViewer("Bearer teacher")).thenReturn(
            new SchedulingCoreClient.Viewer("teacher-1","teacher001","TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.prepare("private-scheduling-key","Bearer teacher","request-1"))
            .getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void activeTcIsBoundToTheStagedActorIdentity() {
        when(core.currentViewer("Bearer tc")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-employee","tc001","TC"));
        controller.prepare("private-scheduling-key","Bearer tc","request-1");
        verify(service).prepare("tc001","request-1");
    }
}

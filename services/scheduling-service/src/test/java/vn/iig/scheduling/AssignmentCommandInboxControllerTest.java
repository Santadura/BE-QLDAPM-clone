package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssignmentCommandInboxControllerTest {
    private final AssignmentCommandInboxService inbox=mock(AssignmentCommandInboxService.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentCommandInboxController controller=
        new AssignmentCommandInboxController(inbox,core,"private-service-key");
    private final AssignmentCommandInboxService.Request request=
        new AssignmentCommandInboxService.Request(
            "ASSIGN_STAFF",null,"req-1",new ObjectMapper().createObjectNode());

    @Test void internalKeyIsCheckedBeforeIdentityOrAnyDatabaseWork() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.stage(null,"Bearer sample",request)).getStatusCode());
        verifyNoInteractions(core,inbox);
    }

    @Test void disabledStaffProfileCannotStageAndStaleJwtCannotBypass() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer(null,"cm001","CM"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.stage("private-service-key","Bearer sample",request)).getStatusCode());
        verifyNoInteractions(inbox);
    }

    @Test void rejectRechecksPrivateKeyAndCurrentCoreIdentity() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.rejectStaged("wrong","Bearer sample","ASSIGN_STAFF","req-1"))
                .getStatusCode());
        verifyNoInteractions(core,inbox);
    }

    @Test void rejectUsesAuthenticatedActorAndStagedOnlyService() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-001","cm001","CM"));
        var receipt=new AssignmentCommandInboxService.Receipt(
            "ASSIGN_STAFF","req-1","REJECTED","some-hash");
        when(inbox.rejectStaged("cm001","CM","ASSIGN_STAFF","req-1"))
            .thenReturn(receipt);
        assertEquals(receipt,controller.rejectStaged(
            "private-service-key","Bearer sample","ASSIGN_STAFF","req-1"));
        verify(inbox).rejectStaged("cm001","CM","ASSIGN_STAFF","req-1");
    }

    @Test void stageForwardsOnlyLiveCurrentRoleAndNeverCommitsAssignments() {
        when(core.currentViewer("Bearer sample")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-001","cm001","CM"));
        var receipt=new AssignmentCommandInboxService.Receipt(
            "ASSIGN_STAFF","req-1","STAGED","some-hash");
        when(inbox.stage("cm001","CM",request)).thenReturn(receipt);
        assertEquals(receipt,
            controller.stage("private-service-key","Bearer sample",request));
        verify(inbox).stage("cm001","CM",request);
    }
}

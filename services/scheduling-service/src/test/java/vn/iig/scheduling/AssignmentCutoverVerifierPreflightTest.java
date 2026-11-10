package vn.iig.scheduling;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentCutoverVerifierPreflightTest {
    private final AssignmentProjectionRepository model=mock(AssignmentProjectionRepository.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentCutoverVerificationService verifier=
        new AssignmentCutoverVerificationService(model,core);

    @Test void onlyExactStagedTeacherCommandCanBeExemptedFromManifestReadiness() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->verifier.verifyStaged("tc001","EDIT_STAFF","key")).getStatusCode());
        verifyNoInteractions(core,model);
    }

    @Test void exactStagedCommandDoesNotMaskAnyOtherUnsettledCommands() {
        var zero=new SchedulingCoreClient.AssignmentManifestSummary(0,0,0,0,0);
        when(core.assignmentManifestSummary()).thenReturn(zero);
        when(core.assignmentManifest("","",250)).thenReturn(List.of());
        when(model.pendingCommandsExceptStaged("tc001","ASSIGN_TEACHER","key"))
            .thenReturn(1L);
        var report=verifier.verifyStaged("tc001","ASSIGN_TEACHER","key");
        assertFalse(report.ready());
        assertEquals("UNRESOLVED_ASSIGNMENT_COMMANDS",report.state());
        verify(model).pendingCommandsExceptStaged("tc001","ASSIGN_TEACHER","key");
        verify(model,never()).pendingAssignmentCommands();
    }

    @Test void normalCutoverNeverExemptsAStagedCandidate() {
        var zero=new SchedulingCoreClient.AssignmentManifestSummary(0,0,0,0,0);
        when(core.assignmentManifestSummary()).thenReturn(zero);
        when(core.assignmentManifest("","",250)).thenReturn(List.of());
        when(model.pendingAssignmentCommands()).thenReturn(1L);
        assertFalse(verifier.verify().ready());
        verify(model).pendingAssignmentCommands();
        verify(model,never()).pendingCommandsExceptStaged(anyString(),anyString(),anyString());
    }
}

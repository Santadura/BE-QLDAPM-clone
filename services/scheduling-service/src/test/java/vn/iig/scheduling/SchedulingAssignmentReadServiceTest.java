package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAssignmentReadServiceTest {
    private final AssignmentProjectionRepository repo=mock(AssignmentProjectionRepository.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final SchedulingAssignmentReadService service=new SchedulingAssignmentReadService(repo,core);
    private final LocalDate monday=LocalDate.parse("2026-11-09");

    @Test void adminWithoutEmployeeCanViewManagementSchedules() {
        when(core.currentViewer("admin")).thenReturn(new SchedulingCoreClient.Viewer(null,"admin","ADMIN"));
        service.teaching("admin",monday);
        verify(repo).list("TEACHING",null,null,monday,false);
        service.staff("admin",monday,"CS");
        verify(repo).list("STAFF",null,"CS",monday,false);
    }

    @Test void teacherCannotBrowseManagerSchedulesOrOtherPeoplesShifts() {
        when(core.currentViewer("worker")).thenReturn(
            new SchedulingCoreClient.Viewer("teacher-001","teacher001","TEACHER"));
        assertThrows(ResponseStatusException.class,()->service.teaching("worker",monday));
        assertThrows(ResponseStatusException.class,()->service.staff("worker",monday,null));
        service.mine("worker",monday);
        verify(repo).list("TEACHING","teacher-001",null,monday,true);
    }

    @Test void tcCannotBrowseCustomerServiceSchedulesAndCmCannotBrowseTeachers() {
        when(core.currentViewer("tc")).thenReturn(new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        when(core.currentViewer("cm")).thenReturn(new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        assertThrows(ResponseStatusException.class,()->service.staff("tc",monday,null));
        assertThrows(ResponseStatusException.class,()->service.teaching("cm",monday));
    }

    @Test void readsNewlyCommittedAssignmentBeforeReturningToBrowser() {
        var changed=new AssignmentProjectionRepository.AssignmentSnapshot(
            "STAFF","cs-new","cs-001","CS Name","CS","class-1",
            "C001","My class","center-01",monday,java.time.LocalTime.of(9,0),
            java.time.LocalTime.of(11,0),"CLASS_SUPPORT","ASSIGNED","cm-1",7,false);
        var entries=List.of(changed);
        when(core.currentViewer("cm")).thenReturn(
            new SchedulingCoreClient.Viewer("cm-1","cm001","CM"));
        when(core.pendingAssignments(500)).thenReturn(entries);
        service.staff("cm",monday,"CS");
        var order=inOrder(repo,core);
        order.verify(core).pendingAssignments(500);
        order.verify(repo).applyBatch(entries);
        order.verify(core).acknowledgeAssignments(entries);
        order.verify(repo).list("STAFF",null,"CS",monday,false);
    }

    @Test void unavailableCoreChangesNeverReturnStaleAssignments() {
        when(core.currentViewer("tc")).thenReturn(
            new SchedulingCoreClient.Viewer("tc-1","tc001","TC"));
        when(core.pendingAssignments(500)).thenThrow(
            new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(ResponseStatusException.class,()->service.teaching("tc",monday));
        verify(repo,never()).list(anyString(),any(),any(),any(),anyBoolean());
    }

    @Test void bootstrapReadsOnceAndDoesNotDiscardRowsOnRetry() {
        when(repo.initialized()).thenReturn(false);
        when(core.exportAssignments()).thenReturn(List.of());
        service.initialize();
        verify(repo).importInitial(List.of());
    }
}

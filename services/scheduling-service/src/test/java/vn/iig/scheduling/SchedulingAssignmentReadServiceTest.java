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

    @Test void bootstrapReadsOnceAndDoesNotDiscardRowsOnRetry() {
        when(repo.initialized()).thenReturn(false);
        when(core.exportAssignments()).thenReturn(List.of());
        service.initialize();
        verify(repo).importInitial(List.of());
    }
}

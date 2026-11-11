package vn.iig.scheduling;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeacherAcademicLeaseCompletionJobTest {
    private final TeacherAcademicLeaseCompletionService service=
        mock(TeacherAcademicLeaseCompletionService.class);
    private final TeacherAcademicLeaseCompletionJob worker=
        new TeacherAcademicLeaseCompletionJob(service);

    @Test void oneUnavailableCoreLeaseDoesNotStarveOthers() {
        when(service.pending(25)).thenReturn(List.of(
            new TeacherAcademicLeaseCompletionService.Key("tc001","bad"),
            new TeacherAcademicLeaseCompletionService.Key("tc001","good")));
        doThrow(new IllegalStateException("Core outage"))
            .when(service).complete("tc001","bad");
        assertDoesNotThrow(worker::sweep);
        verify(service).complete("tc001","bad");
        verify(service).complete("tc001","good");
    }

    @Test void failedScanNeverInventsLeaseCompletion() {
        when(service.pending(25)).thenThrow(new IllegalStateException("DB offline"));
        assertDoesNotThrow(worker::sweep);
        verify(service,never()).complete(anyString(),anyString());
    }
}

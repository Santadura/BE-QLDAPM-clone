package vn.iig.payroll;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayrollAttendanceReferenceControllerTest {
    private final PayrollAttendanceReferenceRelay relay=mock(PayrollAttendanceReferenceRelay.class);
    private final PayrollAttendanceReferenceRepository repo=mock(PayrollAttendanceReferenceRepository.class);
    private final PayrollAttendanceEvidenceClient attendance=
        mock(PayrollAttendanceEvidenceClient.class);
    private final PayrollAttendanceReferenceController controller=
        new PayrollAttendanceReferenceController(relay,repo,attendance,"payroll-secret");

    private void stableSource() {
        when(attendance.readiness()).thenReturn(
            new PayrollAttendanceEvidenceClient.Snapshot(9,4,3,5,
                java.util.List.of()));
    }

    @Test void privateTokenRequiredBeforeAnyDataReads() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify("wrong")).getStatusCode());
        verifyNoInteractions(relay,repo,attendance);
    }

    @Test void returnsOnlyEvidenceCursorNotFinancialInformation() {
        stableSource();
        when(repo.cursor()).thenReturn(9L);
        when(repo.eventCount()).thenReturn(4L);
        var response=controller.verify("payroll-secret");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertTrue(response.getBody().ready());
        assertEquals(9L,response.getBody().lastEventId());
        assertEquals(4L,response.getBody().eventCount());
        assertEquals(3L,response.getBody().attendanceGeneration());
        assertEquals(5L,response.getBody().schedulingGeneration());
        verify(attendance,times(2)).readiness();
        verify(repo).divergentLatestRows();
        verify(relay).requireCaughtUp();
    }

    @Test void failedSourceCatchUpCannotBeReportedReady() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "source unavailable")).when(relay).requireCaughtUp();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("payroll-secret")).getStatusCode());
        verifyNoInteractions(repo,attendance);
    }

    @Test void relayCaughtUpAloneDoesNotProveImmutablePayrollParity() {
        stableSource();
        when(repo.cursor()).thenReturn(9L);
        when(repo.eventCount()).thenReturn(4L);
        when(repo.divergentLatestRows()).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("payroll-secret")).getStatusCode());
    }

    @Test void concurrentAttendanceGenerationAbaBlocksFinality() {
        when(attendance.readiness()).thenReturn(
            new PayrollAttendanceEvidenceClient.Snapshot(9,4,3,5,java.util.List.of()),
            new PayrollAttendanceEvidenceClient.Snapshot(9,4,5,5,java.util.List.of()));
        when(repo.cursor()).thenReturn(9L);
        when(repo.eventCount()).thenReturn(4L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("payroll-secret")).getStatusCode());
    }
}

package vn.iig.payroll;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayrollAttendanceReferenceControllerTest {
    private final PayrollAttendanceReferenceRelay relay=mock(PayrollAttendanceReferenceRelay.class);
    private final PayrollAttendanceReferenceRepository repo=mock(PayrollAttendanceReferenceRepository.class);
    private final PayrollAttendanceReferenceController controller=
        new PayrollAttendanceReferenceController(relay,repo,"payroll-secret");

    @Test void privateTokenRequiredBeforeAnyDataReads() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify("wrong")).getStatusCode());
        verifyNoInteractions(relay,repo);
    }

    @Test void returnsOnlyEvidenceCursorNotFinancialInformation() {
        when(repo.cursor()).thenReturn(9L);
        when(repo.eventCount()).thenReturn(4L);
        var response=controller.verify("payroll-secret");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertTrue(response.getBody().ready());
        assertEquals(9L,response.getBody().lastEventId());
        assertEquals(4L,response.getBody().eventCount());
        verify(relay).requireCaughtUp();
    }

    @Test void failedSourceCatchUpCannotBeReportedReady() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "source unavailable")).when(relay).requireCaughtUp();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->controller.verify("payroll-secret")).getStatusCode());
        verifyNoInteractions(repo);
    }
}

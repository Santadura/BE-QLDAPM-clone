package vn.iig.payroll;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayrollAttendanceReconciliationControllerTest {
    private final PayrollAttendanceReconciliationService service=
        mock(PayrollAttendanceReconciliationService.class);
    private final PayrollAttendanceReconciliationController controller=
        new PayrollAttendanceReconciliationController(service,"private-payroll-key");

    @Test void onlyPayrollPrivateKeyCanRequestAttendanceProof() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.compare(null,"2026-10")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.compare("attendance-key","2026-10")).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void matchingEvidenceIsPrivateReadOnlyReport() {
        var report=new PayrollAttendanceReconciliationService.Report(
            true,"MATCH","2026-10",2,9,4,3,5,0,List.of());
        when(service.compare("2026-10")).thenReturn(report);
        var result=controller.compare("private-payroll-key","2026-10");
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertEquals(report,result.getBody());
    }

    @Test void divergentAttendanceReferenceReturnsConflictInsteadOfApproval() {
        var report=new PayrollAttendanceReconciliationService.Report(
            false,"MISMATCH","2026-10",2,9,4,3,5,1,List.of("teach-1"));
        when(service.compare("2026-10")).thenReturn(report);
        assertEquals(HttpStatus.CONFLICT,
            controller.compare("private-payroll-key","2026-10").getStatusCode());
    }
}

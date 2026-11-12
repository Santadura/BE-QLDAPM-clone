package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateReconciliationControllerTest {
    final AcademicRosterCandidateReconciliation svc=
        mock(AcademicRosterCandidateReconciliation.class);
    final AcademicRosterCandidateReconciliationController controller=
        new AcademicRosterCandidateReconciliationController(svc,"private");

    @Test void missingKeyBlocksAuthorizationAndAllEvidenceReads() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect(null,"Bearer admin","admin","key"))
            .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect("wrong","Bearer admin","admin","key"))
            .getStatusCode());
        verifyNoInteractions(svc);
    }
    @Test void privateAdminDiagnosticIsNeverCacheable() {
        var diagnosis=new AcademicRosterCandidateReconciliation.Diagnosis(
            "CORE_ONLY_ORPHAN","admin","key","OPEN",null,
            false,false,false,true);
        when(svc.inspect("Bearer admin","admin","key")).thenReturn(diagnosis);
        var response=controller.inspect("private","Bearer admin","admin","key");
        assertEquals(diagnosis,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(svc).inspect("Bearer admin","admin","key");
    }
}

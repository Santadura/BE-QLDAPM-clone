package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCandidateReconciliationControllerTest {
    private final AcademicCandidateReconciliation service=
        mock(AcademicCandidateReconciliation.class);
    private final AcademicCandidateReconciliationController controller=
        new AcademicCandidateReconciliationController(service,"private-key");

    @Test void wrongOrMissingKeyNeverTouchesCoreOrAcademic() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                controller.inspect(null,"Bearer admin","actor","key"))
            .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                controller.inspect("invalid","Bearer admin","actor","key"))
            .getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void privateInspectionAlwaysDisablesBrowserCaching() {
        var result=new AcademicCandidateReconciliation.Diagnosis(
            "CORE_ONLY_ORPHAN","actor","key","OPEN",null,false,false,true);
        when(service.inspect("Bearer admin","actor","key")).thenReturn(result);
        var response=controller.inspect("private-key","Bearer admin","actor","key");
        assertEquals(result,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).inspect("Bearer admin","actor","key");
    }
}

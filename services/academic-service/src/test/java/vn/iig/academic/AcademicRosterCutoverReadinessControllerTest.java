package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCutoverReadinessControllerTest {
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicRosterCutoverReadiness readiness=
        mock(AcademicRosterCutoverReadiness.class);
    final AcademicRosterCutoverReadinessController controller=
        new AcademicRosterCutoverReadinessController(identity,readiness,"key");

    @Test void unauthorizedKeyNeverReadsIdentityOrCoreRosterHistory() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect(null,"Bearer admin")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect("wrong","Bearer admin")).getStatusCode());
        verifyNoInteractions(identity,readiness);
    }
    @Test void liveTeacherCannotInspectCrossDatabaseStudentPII() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect("key","Bearer teacher")).getStatusCode());
        verifyNoInteractions(readiness);
    }
    @Test void adminSuccessMustDisableAllClientCaching() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        var proof=new AcademicRosterCutoverReadiness.Evidence(2,11,15,4,3,7);
        when(readiness.verify()).thenReturn(proof);
        var response=controller.inspect("key","Bearer admin");
        assertEquals(proof,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(readiness).verify();
    }
}

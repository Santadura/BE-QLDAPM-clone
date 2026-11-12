package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterReadinessControllerTest {
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicRosterReadiness readiness=mock(AcademicRosterReadiness.class);
    final AcademicRosterReadinessController controller=
        new AcademicRosterReadinessController(identity,readiness,"private-key");

    @Test void serviceKeyMustBeValidatedBeforeCheckingUserOrStudentData() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                controller.inspect(null,"Bearer admin")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                controller.inspect("invalid","Bearer admin")).getStatusCode());
        verifyNoInteractions(identity,readiness);
    }
    @Test void currentAdminRoleRequiredForCrossDatabaseInspection() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,()->
                controller.inspect("private-key","Bearer teacher"))
            .getStatusCode());
        verifyNoInteractions(readiness);
    }
    @Test void authenticatedProofIsNeverCacheable() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        var proof=new AcademicRosterReadiness.Evidence(2,3,1,1,2);
        when(readiness.verify()).thenReturn(proof);
        var response=controller.inspect("private-key","Bearer admin");
        assertEquals(proof,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }
}

package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCrossDomainCutoverReadinessControllerTest {
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicFullCutoverReadiness readiness=
        mock(AcademicFullCutoverReadiness.class);
    final AcademicCrossDomainCutoverReadinessController controller=
        new AcademicCrossDomainCutoverReadinessController(identity,readiness,"private");

    @Test void missingOrWrongKeyNeverCallsIdentityOrCrossDatabaseProof() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect(null,"Bearer admin")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect("wrong","Bearer admin")).getStatusCode());
        verifyNoInteractions(identity,readiness);
    }
    @Test void liveTeacherCannotInspectMultiDomainCutoverEvidence() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.inspect("private","Bearer teacher")).getStatusCode());
        verifyNoInteractions(readiness);
    }
    @Test void liveAdminGetsOnlyNonCacheableReadOnlyProof() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        var academic=new AcademicCrossDomainCutoverReadiness.Evidence(
            2,2,2,2,11,20,15,30,4,2);
        var evidence=new AcademicFullCutoverReadiness.Evidence(academic,
            new AcademicDependentCutoverClient.Proof(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
        when(readiness.verify()).thenReturn(evidence);
        var response=controller.inspect("private","Bearer admin");
        assertEquals(evidence,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(readiness).verify();
    }
}

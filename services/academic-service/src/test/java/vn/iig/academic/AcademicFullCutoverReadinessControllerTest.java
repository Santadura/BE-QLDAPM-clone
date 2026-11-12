package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicFullCutoverReadinessControllerTest {
    final AcademicFullCutoverReadiness verifier=
        mock(AcademicFullCutoverReadiness.class);
    final AcademicFullCutoverReadinessController controller=
        new AcademicFullCutoverReadinessController(verifier,"internal-test-key");

    @Test void privateKeyIsMandatoryEvenForReadOnlyCutoverEvidence() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.verify("wrong-key")).getStatusCode());
        verifyNoInteractions(verifier);
    }

    @Test void authenticatedProofRemainsNonCacheable() {
        var academic=new AcademicCrossDomainCutoverReadiness.Evidence(
            2,3,4,5,7,8,9,10,11,12);
        var proof=new AcademicFullCutoverReadiness.Evidence(academic,null);
        when(verifier.verify()).thenReturn(proof);
        var response=controller.verify("internal-test-key");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals(proof,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(verifier).verify();
    }
}

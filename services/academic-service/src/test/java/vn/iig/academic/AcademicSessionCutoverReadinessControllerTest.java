package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCutoverReadinessControllerTest {
    AcademicSessionCutoverReadiness verifier=mock(AcademicSessionCutoverReadiness.class);
    AcademicSessionCutoverReadinessController controller=
        new AcademicSessionCutoverReadinessController(verifier,"private-key");

    @Test void rejectsMissingOrWrongKeyWithoutTouchingState() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.readiness(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.readiness("wrong")).getStatusCode());
        verifyNoInteractions(verifier);
    }
    @Test void returnsReadOnlyEvidenceWithoutChangingWriter() {
        var evidence=new AcademicSessionCutoverReadiness.Evidence(2,5,0,1,4,8,9);
        when(verifier.verify()).thenReturn(evidence);
        assertEquals(evidence,controller.readiness("private-key").getBody());
        verify(verifier).verify();
    }
}

package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeSessionServiceTest {
    final AcademicCoreClient core=mock(AcademicCoreClient.class);
    final AcademicFullCutoverReadiness readiness=
        mock(AcademicFullCutoverReadiness.class);
    final AcademicNativeSessionRepository ledger=
        mock(AcademicNativeSessionRepository.class);
    final AcademicNativeSessionService service=
        new AcademicNativeSessionService(core,readiness,ledger);

    @Test void liveNonAdminCannotActivateIndependentWriter() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AcademicCoreClient.Principal("cs001","ROLE_CS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.activate("class-1","Bearer cs")).getStatusCode());
        verifyNoInteractions(readiness,ledger);
    }
    @Test void nativeMutationRequiresAStableIdempotencyKey() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",
                new ObjectMapper().createArrayNode(),"Bearer admin",null))
            .getStatusCode());
        verifyNoInteractions(readiness,ledger);
    }
    @Test void independentPreflightOutageFailsClosedBeforeAnyLocalWrite() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(readiness.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Downstream Scheduling unreachable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",
                new ObjectMapper().createArrayNode(),"Bearer admin","key-1"))
            .getStatusCode());
        verifyNoInteractions(ledger);
    }
}

package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateDispositionControllerTest {
    final AcademicSessionCandidateDispositionService service=
        mock(AcademicSessionCandidateDispositionService.class);
    final AcademicSessionCandidateDispositionController controller=
        new AcademicSessionCandidateDispositionController(service,"private-session-key");
    final AcademicSessionCandidateDispositionService.Request request=
        new AcademicSessionCandidateDispositionService.Request(
            "key-1","b381ee38-d42f-48e7-bbf8-24198e329503");

    @Test void invalidKeyIsRejectedBeforeAnyUserOrPostgresAccess() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.discard(null,"Bearer admin",request)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.discard("wrong","Bearer admin",request)).getStatusCode());
        verifyNoInteractions(service);
    }

    @Test void authorizedPrivateRequestRetainsNoStoreCachePolicy() {
        var receipt=new AcademicSessionCandidateDispositionRepository.Receipt(
            request.requestKey(),request.commandId(),"REHEARSAL_DISCARDED");
        when(service.discard("Bearer admin",request)).thenReturn(receipt);
        var response=controller.discard("private-session-key","Bearer admin",request);
        assertEquals(receipt,response.getBody());
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
    }

    @Test void endpointCanOnlyExistWhenOptInFlagIsExplicitlyTrue() {
        var flag=AcademicSessionCandidateDispositionController.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.academic.session-disposition-enabled"},
            flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }
}

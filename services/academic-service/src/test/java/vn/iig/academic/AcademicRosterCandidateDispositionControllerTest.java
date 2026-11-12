package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateDispositionControllerTest {
    final AcademicRosterCandidateDispositionService service=
        mock(AcademicRosterCandidateDispositionService.class);
    final AcademicRosterCandidateDispositionController controller=
        new AcademicRosterCandidateDispositionController(service,"private");
    final AcademicRosterCandidateDispositionService.Request request=
        new AcademicRosterCandidateDispositionService.Request(
            "key-1","b381ee38-d42f-48e7-bbf8-24198e329503");

    @Test void missingServiceKeyCannotReadOrWritePrivateRehearsals() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.discard(null,"Bearer admin",request)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.discard("wrong","Bearer admin",request)).getStatusCode());
        verifyNoInteractions(service);
    }
    @Test void exactPrivateAdminRequestReturnsNoStoreAck() {
        var receipt=new AcademicRosterCandidateDispositionService.Receipt(
            request.requestKey(),request.commandId(),"REHEARSAL_DISCARDED");
        when(service.discard("Bearer admin",request)).thenReturn(receipt);
        var response=controller.discard("private","Bearer admin",request);
        assertEquals(receipt,response.getBody());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(service).discard("Bearer admin",request);
    }
}

package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateControllerTest {
    AcademicSessionCandidateService service=mock(AcademicSessionCandidateService.class);
    AcademicSessionCandidateController controller=
        new AcademicSessionCandidateController(service,"private-key");
    @Test void serviceKeyMustPrecedeAnyCandidateMutation() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.stage("wrong","Bearer admin",null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.stage(null,"Bearer admin",null)).getStatusCode());
        verifyNoInteractions(service);
    }
    @Test void onlyPrivateKeyMayReachValidatedService() {
        var req=new AcademicSessionCandidateService.Request("key","c1","s1",1,1,
            "CANCEL","2027-02-12","09:00","11:00","CANCELLED");
        controller.stage("private-key","Bearer admin",req);
        verify(service).stage("Bearer admin",req);
    }
}

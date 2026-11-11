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
        var receipt=new AcademicSessionCandidateRepository.Receipt(
            "00000000-0000-4000-8000-000000000111","key","c1","s1",1,1,"STAGED");
        when(service.stage("Bearer admin",req)).thenReturn(receipt);
        assertEquals(receipt,controller.stage("private-key","Bearer admin",req));
        var ordered=inOrder(service);
        ordered.verify(service).stage("Bearer admin",req);
        ordered.verify(service).acknowledge("Bearer admin",req,receipt);
    }
}

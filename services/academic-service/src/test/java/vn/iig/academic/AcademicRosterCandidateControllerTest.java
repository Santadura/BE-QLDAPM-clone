package vn.iig.academic;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateControllerTest {
    final AcademicRosterCandidateService service=mock(AcademicRosterCandidateService.class);
    final AcademicRosterCandidateController controller=
        new AcademicRosterCandidateController(service,"private");

    @Test void invalidServiceKeyPreventsEvenAuthorizationLookups() {
        var req=new AcademicRosterCandidateService.Request(
            "req","TARGET","target-1",1,1,"UPSERT",BigDecimal.ONE);
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.stage(null,"Bearer admin",req)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.stage("incorrect","Bearer admin",req)).getStatusCode());
        verifyNoInteractions(service);
    }
    @Test void validPrivateCallDelegatesToGuardedIndependentTransaction() {
        var req=new AcademicRosterCandidateService.Request(
            "req","RESULT","result-1",4,2,"UPSERT",new BigDecimal("8.50"));
        var receipt=new AcademicRosterCandidateRepository.Receipt(
            "command","req","RESULT","result-1",4,"STAGED");
        when(service.stage("Bearer admin",req)).thenReturn(receipt);
        assertEquals(receipt,controller.stage("private","Bearer admin",req));
        verify(service).stage("Bearer admin",req);
    }
}

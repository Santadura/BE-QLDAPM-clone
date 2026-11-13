package vn.iig.hr;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import vn.iig.hr.CertificateModels.*;

class HrSelfCertificateControllerTest {
    private final HrCoreClient core=mock(HrCoreClient.class);
    private final CertificateRepository repository=mock(CertificateRepository.class);
    private final HrSelfCertificateController controller=
        new HrSelfCertificateController(core,repository);
    @Test void currentWorkerCanReadOnlyTheirOwnHRRows() {
        when(core.currentEmployeeId("Bearer worker")).thenReturn("emp-1");
        when(repository.imported()).thenReturn(true);
        var original=new CertificateData("c-1","emp-1","IELTS",8.0,
            null,null,null,null,null,"ACTIVE",Instant.now(),1);
        when(repository.byEmployee("emp-1")).thenReturn(List.of(original));
        assertEquals(List.of(original.publicView()),controller.list("Bearer worker"));
        verify(repository).byEmployee("emp-1");
        verify(repository,never()).byEmployee("emp-2");
    }
    @Test void revokedSelfSessionFailsBeforeDatabaseRead() {
        when(core.currentEmployeeId("Bearer revoked")).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class,()->
            controller.list("Bearer revoked"));
        verifyNoInteractions(repository);
    }
    @Test void originalNoEmployeeProfileRespondsEmpty() {
        when(repository.imported()).thenReturn(true);
        assertEquals(List.of(),controller.list("Bearer admin"));
        verify(repository,never()).byEmployee(anyString());
    }
}

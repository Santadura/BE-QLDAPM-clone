package vn.iig.hr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.hr.CertificateModels.*;

class CertificateServiceTest {
    private final CertificateRepository repo=mock(CertificateRepository.class);
    private final HrCoreClient core=mock(HrCoreClient.class);
    private final CertificateService service=new CertificateService(repo,core);

    private CertificateRequest valid() {
        return new CertificateRequest("IELTS",8.5,null,
            LocalDate.of(2026,1,1),LocalDate.of(2028,1,1),
            "Test center","https://example.com/cert.pdf","ACTIVE");
    }

    @Test void rejectsRequestsBeforeInitialImport() {
        when(repo.imported()).thenReturn(false);
        assertThrows(ResponseStatusException.class,
            ()->service.list("emp-1","Bearer admin"));
        verify(core).checkEmployeeAccess("Bearer admin","emp-1");
        verify(repo,never()).byEmployee(anyString());
    }

    @Test void neverReadsOrWritesWhenCoreDeniesEmployeeScope() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(core).checkEmployeeAccess("Bearer teacher","emp-1");
        assertThrows(ResponseStatusException.class,
            ()->service.create("emp-1",valid(),"Bearer teacher"));
        verifyNoInteractions(repo);
    }

    @Test void refusesInvalidDatesAndScheme() {
        when(repo.imported()).thenReturn(true);
        var badDates=new CertificateRequest("IELTS",8.5,null,
            LocalDate.of(2026,11,9),LocalDate.of(2026,1,1),
            null,null,"ACTIVE");
        assertThrows(ResponseStatusException.class,
            ()->service.create("emp-1",badDates,"Bearer admin"));
        var badUrl=new CertificateRequest("IELTS",8.5,null,null,null,
            null,"file:///secret","ACTIVE");
        assertThrows(ResponseStatusException.class,
            ()->service.create("emp-1",badUrl,"Bearer admin"));
        verify(repo,never()).create(any());
    }

    @Test void bootstrapsLegacyAndAcknowledgesProjectedVersion() {
        CertificateData old=new CertificateData("cert-1","emp-1","IELTS",
            8.0,null,null,null,null,null,"ACTIVE",Instant.now(),1);
        when(repo.imported()).thenReturn(false,true);
        when(core.exportLegacy()).thenReturn(List.of(old));
        when(repo.pending(100)).thenReturn(List.of(new CertificateRepository.Pending(old,1)));
        service.synchronize();
        verify(repo).importInitial(List.of(old));
        verify(core).project(old);
        verify(repo).acknowledge("cert-1",1);
    }

    @Test void neverAcknowledgesWhenProjectionFails() {
        CertificateData row=new CertificateData("cert-1","emp-1","IELTS",
            8.5,null,null,null,null,null,"ACTIVE",Instant.now(),2);
        when(repo.imported()).thenReturn(true);
        when(repo.pending(100)).thenReturn(List.of(new CertificateRepository.Pending(row,2)));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(core).project(row);
        service.synchronize();
        verify(repo,never()).acknowledge(anyString(),anyLong());
    }
}

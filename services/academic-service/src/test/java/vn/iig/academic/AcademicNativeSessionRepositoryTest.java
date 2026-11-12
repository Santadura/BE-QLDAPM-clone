package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeSessionRepositoryTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final AcademicNativeSessionRepository repo=
        new AcademicNativeSessionRepository(db,new ObjectMapper());

    @Test void invalidCommandKeyNeverQueriesOrMutatesNativeStore() {
        var request=new ObjectMapper().createArrayNode();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->repo.execute("admin","class-1","PLAN","has spaces",
                request,2,2)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void unsupportedOperationDoesNotStageAReceiptOrEvent() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->repo.execute(
                "admin","class-1","DELETE","key",
                new ObjectMapper().createObjectNode(),2,2)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void generationWithoutFrozenActivationCannotMutate() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.execute(
                "admin","class-1","PLAN","key",
                new ObjectMapper().createArrayNode(),2,2)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void activationWithoutSourceDateEvidenceCannotMutate() {
        var request=new AcademicNativeSessionRepository.Activation(
            "class-1","READY",LocalDate.parse("2026-12-01"),
            LocalDate.parse("2026-11-01"),2,2,0,"admin");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->repo.activate(request)).getStatusCode());
        verifyNoInteractions(db);
    }
}

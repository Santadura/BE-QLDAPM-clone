package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateDispositionRepositoryTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final AcademicRosterCandidateRepository candidates=
        mock(AcademicRosterCandidateRepository.class);
    final AcademicRosterCandidateDispositionRepository repository=
        new AcademicRosterCandidateDispositionRepository(db,candidates);

    @Test void rejectInvalidProofIdentifiersBeforeAnyDatabaseQueries() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->repository.discard("admin","key","not-a-command","admin"))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->repository.discard("admin","key",
                "b381ee38-d42f-48e7-bbf8-24198e329503","bad actor"))
            .getStatusCode());
        verifyNoInteractions(db,candidates);
    }
    @Test void noCommittedAcademicOutboxCannotFabricateDisposition() {
        String command="b381ee38-d42f-48e7-bbf8-24198e329503";
        when(candidates.committedEvidence("admin","key",command))
            .thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,"Academic outbox missing"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repository.discard("admin","key",command,"admin"))
            .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void noConfirmedCoreHandbackReceiptCannotDisposeRehearsal() {
        String command="b381ee38-d42f-48e7-bbf8-24198e329503";
        when(candidates.committedEvidence("admin","key",command)).thenReturn(
            new AcademicRosterCandidateRepository.Evidence(
                "admin","key","a".repeat(64),"TARGET","target-1",
                5,6,2,"UPSERT",command,"b".repeat(64)));
        when(db.queryForObject(anyString(),eq(Long.class),any(),any(),any(),any()))
            .thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repository.discard("admin","key",command,"admin"))
            .getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

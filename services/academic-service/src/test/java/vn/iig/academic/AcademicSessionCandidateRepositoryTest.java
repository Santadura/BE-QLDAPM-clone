package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateRepositoryTest {
    JdbcTemplate db=mock(JdbcTemplate.class);
    AcademicSessionCandidateRepository repo=
        new AcademicSessionCandidateRepository(db,new ObjectMapper());
    AcademicSessionCandidateRepository.Plan plan() {
        return new AcademicSessionCandidateRepository.Plan(
            "admin","key","a".repeat(64),"class-1","lesson-1",5,3,
            "RESCHEDULE",LocalDate.parse("2027-02-12"),
            LocalTime.parse("09:00"),LocalTime.parse("11:00"),"PLANNED",4,5,6);
    }
    @Test void staleSessionCannotCreateAnyCandidateEvent() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.stage(plan())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void unpromotedRehearsalsIncludeReleasedClaimsUntilExplicitDisposition() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_candidate_commands",
            Long.class)).thenReturn(3L);
        assertEquals(3,repo.unpromotedRehearsalCount());
    }

    @Test void missingRehearsalLedgerNeverCountsAsEmpty() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_candidate_commands",
            Long.class)).thenReturn(null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,repo::unpromotedRehearsalCount)
            .getStatusCode());
    }

}

package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
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
    @SuppressWarnings({"rawtypes","unchecked"})
    private void originalActivation(String owner) throws Exception {
        when(db.query(startsWith("SELECT writer_generation,roster_generation,"),
                any(RowMapper.class),eq("class-1"))).thenAnswer(call-> {
            RowMapper mapper=call.getArgument(1);
            ResultSet rs=mock(ResultSet.class);
            when(rs.getLong(1)).thenReturn(4L);
            when(rs.getLong(2)).thenReturn(6L);
            when(rs.getLong(3)).thenReturn(9L);
            when(rs.getString(4)).thenReturn("READY");
            when(rs.getObject(5,LocalDate.class))
                .thenReturn(LocalDate.parse("2027-02-10"));
            when(rs.getObject(6,LocalDate.class))
                .thenReturn(LocalDate.parse("2027-02-12"));
            when(rs.getString(7)).thenReturn(owner);
            when(rs.getString(8)).thenReturn("REHEARSAL_ONLY");
            return List.of(mapper.mapRow(rs,0));
        });
    }

    private AcademicNativeSessionRepository.Activation activation() {
        return new AcademicNativeSessionRepository.Activation(
            "class-1","READY",LocalDate.parse("2027-02-10"),
            LocalDate.parse("2027-02-12"),4,6,9,"admin");
    }

    @Test void exactFrozenActivationReplayCannotImportSameLessonsTwice()
            throws Exception {
        originalActivation("admin");
        when(db.queryForObject(contains("FROM academic_class_sessions_shadow"),
            eq(Long.class),eq("class-1"))).thenReturn(2L);
        when(db.queryForObject(contains("source_imported=TRUE"),
            eq(Long.class),eq("class-1"))).thenReturn(2L);
        assertEquals(2L,repo.activate(activation()));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void changedOperatorCannotStealAnAlreadyCommittedActivation()
            throws Exception {
        originalActivation("different-admin");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.activate(activation())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void mismatchBetweenSourceAndImportedBaselineRefusesReplay()
            throws Exception {
        originalActivation("admin");
        when(db.queryForObject(contains("FROM academic_class_sessions_shadow"),
            eq(Long.class),eq("class-1"))).thenReturn(3L);
        when(db.queryForObject(contains("source_imported=TRUE"),
            eq(Long.class),eq("class-1"))).thenReturn(2L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.activate(activation())).getStatusCode());
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

package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionSourceRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicSessionSourceRepository repo=
        new AcademicSessionSourceRepository(db,new ObjectMapper());
    private final String payload="{\"class_session_id\":\"lesson-1\","+
        "\"class_id\":\"class-1\",\"date\":\"2027-02-11\","+
        "\"start_time\":\"09:00:00\",\"end_time\":\"11:00:00\","+
        "\"status\":\"PLANNED\"}";
    private AcademicSessionSourceClient.Event event(String hash,String kind) {
        return new AcademicSessionSourceClient.Event(7,"lesson-1","class-1",
            kind,11,payload,hash);
    }
    @Test void rejectsHashMismatchBeforeAnyDatabaseWrite() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(event("0".repeat(64),"UPSERT"),false))
            .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void rejectsMismatchedSourceIdentityBeforePersisting() {
        String bad="{\"class_session_id\":\"other\",\"class_id\":\"class-1\","+
            "\"date\":\"2027-02-11\",\"start_time\":\"09:00\","+
            "\"end_time\":\"11:00\",\"status\":\"PLANNED\"}";
        var e=new AcademicSessionSourceClient.Event(7,"lesson-1","class-1",
            "UPSERT",11,bad,AcademicSessionSourceRepository.sha256(bad));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(e,false)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void deleteTombstonesAreRetainedAndCursorMovesWithinSameTransaction() {
        when(db.update(startsWith("INSERT INTO academic_session_source_receipts"),
            any(Object[].class))).thenReturn(1);
        var e=event(AcademicSessionSourceRepository.sha256(payload),"DELETE");
        repo.apply(e,true);
        verify(db).update(startsWith("INSERT INTO academic_session_owned_projection"),
            eq("lesson-1"),eq("class-1"),eq(7L),eq(11L),eq("DELETE"),eq(payload));
        verify(db).update(startsWith("UPDATE academic_session_history_cursor"),
            eq(7L));
    }
}

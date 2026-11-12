package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterSourceRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicRosterSourceRepository repo=
        new AcademicRosterSourceRepository(db,new ObjectMapper());

    private AcademicRosterSourceClient.Event event(
            String type,String id,String kind,String json) {
        return new AcademicRosterSourceClient.Event(1,type,id,kind,1,
            json,AcademicSessionSourceRepository.sha256(json));
    }

    @Test void alteredHashCannotCreateAnImmutableReceipt() {
        var original=event("STUDENT","student-1","UPSERT",
            "{\"student_id\":\"student-1\",\"student_code\":\"S1\",\"full_name\":\"Alice\",\"status\":\"Active\"}");
        var forged=new AcademicRosterSourceClient.Event(original.eventId(),
            original.entityType(),original.entityId(),original.eventKind(),
            original.sourceRevision(),original.payloadJson(),"a".repeat(64));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(forged)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void swappedMembershipIdentityCannotBeImported() {
        var event=event("MEMBERSHIP","member-1","UPSERT",
            "{\"class_student_id\":\"member-2\",\"class_id\":\"class-1\",\"student_id\":\"student-1\",\"status\":\"ACTIVE\"}");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(event)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingStudentCodeCannotEnterIndependentProjection() {
        var event=event("STUDENT","student-1","DELETE",
            "{\"student_id\":\"student-1\",\"full_name\":\"Alice\",\"status\":\"Active\"}");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(event)).getStatusCode());
        verifyNoInteractions(db);
    }
}

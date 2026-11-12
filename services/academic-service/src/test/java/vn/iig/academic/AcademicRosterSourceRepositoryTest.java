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
    @Test void targetInsertWritesTypedAcademicShadowAndCursor() {
        var target=event("TARGET","target-1","UPSERT",
            "{\"student_target_id\":\"target-1\",\"student_id\":\"student-1\","+
            "\"course_id\":\"course-1\",\"target_type\":\"READING\",\"target_value\":6.50}");
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        assertDoesNotThrow(()->repo.apply(target));
        verify(db).update(startsWith("INSERT INTO academic_student_targets_shadow"),
            any(Object[].class));
        verify(db).update(startsWith("UPDATE academic_roster_history_cursor"),
            any(Object[].class));
    }
    @Test void resultDeleteRetainsTypedAcademicTombstone() {
        var result=event("RESULT","result-1","DELETE",
            "{\"student_result_id\":\"result-1\",\"student_id\":\"student-1\","+
            "\"class_id\":\"class-1\",\"score\":8.25,\"evaluated_by\":\"teacher-1\","+
            "\"evaluated_at\":\"2026-11-12T00:00:00+00:00\"}");
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        assertDoesNotThrow(()->repo.apply(result));
        verify(db).update(startsWith("INSERT INTO academic_student_results_shadow"),
            any(Object[].class));
    }
    @Test void negativeStudentResultCannotPersistReceipt() {
        var result=event("RESULT","result-1","UPSERT",
            "{\"student_result_id\":\"result-1\",\"student_id\":\"student-1\","+
            "\"class_id\":\"class-1\",\"score\":-1,\"evaluated_by\":\"teacher-1\","+
            "\"evaluated_at\":\"2026-11-12T00:00:00+00:00\"}");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(result)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void mismatchedTargetIdentityCannotCreateSourceReceipt() {
        var target=event("TARGET","target-1","UPSERT",
            "{\"student_target_id\":\"target-2\",\"student_id\":\"student-1\","+
            "\"course_id\":\"course-1\",\"target_type\":\"READING\",\"target_value\":6.50}");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->repo.apply(target)).getStatusCode());
        verifyNoInteractions(db);
    }

}

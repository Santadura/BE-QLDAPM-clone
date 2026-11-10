package vn.iig.attendance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class AttendanceEventProjectionRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AttendanceEventProjectionRepository repo=
        new AttendanceEventProjectionRepository(db,new ObjectMapper());

    private static final String JSON=
        "{\"teaching_schedule_id\":\"teach-1\",\"attendance_id\":\"att-1\","+
        "\"status\":\"PRESENT\",\"recorded_by\":\"cs-001\"}";

    private static String hash(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(source.getBytes(StandardCharsets.UTF_8)));
    }

    private AttendanceSourceClient.Event event(long id,String kind,String payload)
            throws Exception {
        return new AttendanceSourceClient.Event(id,"teach-1",kind,payload,
            hash(payload));
    }

    @Test void sourcePayloadIsCommittedToIndependentAttendanceDatabase()
            throws Exception {
        when(db.query(startsWith("SELECT payload_hash"),
            any(RowMapper.class),eq(7L))).thenReturn(List.of());
        repo.apply(event(7,"UPSERT",JSON));
        verify(db).update(
            startsWith("INSERT INTO attendance_teacher_source_records"),
            eq("teach-1"),eq(7L),eq("UPSERT"),eq(JSON));
        verify(db).update(startsWith("INSERT INTO attendance_import_receipts"),
            eq(7L),eq("teach-1"),eq(hash(JSON)));
    }

    @Test void replayedSameEventIsAnIdempotentNoop() throws Exception {
        when(db.query(startsWith("SELECT payload_hash"),
            any(RowMapper.class),eq(7L))).thenReturn(List.of(hash(JSON)));
        repo.apply(event(7,"UPSERT",JSON));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void duplicateEventIdWithDifferentDigestIsRejected() throws Exception {
        when(db.query(startsWith("SELECT payload_hash"),
            any(RowMapper.class),eq(7L))).thenReturn(List.of("a".repeat(64)));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->repo.apply(event(7,"UPSERT",JSON))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void untrustedInvalidDigestNeverReachesDatabase() throws Exception {
        var event=new AttendanceSourceClient.Event(7,"teach-1","UPSERT",
            JSON,"f".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->repo.apply(event)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void mismatchedTeachingScheduleOrUnsupportedStatusIsRejected()
            throws Exception {
        String bad=JSON.replace("teach-1","teach-other");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->repo.apply(event(7,"UPSERT",bad))).getStatusCode());
        String invalid=JSON.replace("PRESENT","APPROVED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->repo.apply(event(8,"UPSERT",invalid))).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void deletionIsARealTombstoneNotAnErasedHistoricalFact()
            throws Exception {
        when(db.query(startsWith("SELECT payload_hash"),
            any(RowMapper.class),eq(8L))).thenReturn(List.of());
        repo.apply(event(8,"DELETE",JSON));
        verify(db).update(
            startsWith("INSERT INTO attendance_teacher_source_records"),
            eq("teach-1"),eq(8L),eq("DELETE"),eq(JSON));
    }
}

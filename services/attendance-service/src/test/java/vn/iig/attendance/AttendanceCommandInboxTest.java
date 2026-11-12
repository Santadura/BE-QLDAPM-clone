package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceCommandInboxTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AttendanceCommandInbox inbox=new AttendanceCommandInbox(db,new ObjectMapper());

    @Test void sameKeyDifferentHashConflictsBeforeCallingCore() {
        when(db.queryForMap(startsWith("SELECT request_hash"),any(Object[].class)))
            .thenReturn(Map.of("request_hash","original"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->inbox.stage("cs001","class-1","teach-1","key","changed")).getStatusCode());
    }

    @Test void sameCommittedReceiptCanBeVerifiedAgain() throws Exception {
        var reply=new ObjectMapper().readTree("{\"status\":\"PRESENT\"}");
        when(db.update(startsWith("UPDATE attendance_write_command_inbox"),
            any(Object[].class))).thenReturn(0);
        when(db.queryForMap(startsWith("SELECT request_hash,response_payload"),
            any(Object[].class))).thenReturn(Map.of(
                "request_hash","hash","response_payload","{\"status\":\"PRESENT\"}"));
        inbox.complete("cs001","class-1","teach-1","key","hash",reply);
    }

    @Test void divergentCommittedResultNeverGetsSilentlyAcknowledged() throws Exception {
        var reply=new ObjectMapper().readTree("{\"status\":\"ABSENT\"}");
        when(db.queryForMap(startsWith("SELECT request_hash,response_payload"),
            any(Object[].class))).thenReturn(Map.of(
                "request_hash","hash","response_payload","{\"status\":\"PRESENT\"}"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->inbox.complete("cs001","class-1","teach-1","key","hash",reply)).getStatusCode());
    }
    @Test void ambiguousCoreResponsesRemainCutoverBlockers() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM attendance_write_command_inbox WHERE state='PENDING'",
            Long.class)).thenReturn(2L);
        assertEquals(2L,inbox.pendingCount());
    }

    @Test void missingIndependentLedgerMustNotCountAsZero() {
        when(db.queryForObject(
            "SELECT COUNT(*) FROM attendance_write_command_inbox WHERE state='PENDING'",
            Long.class)).thenReturn(null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,inbox::pendingCount).getStatusCode());
    }

}

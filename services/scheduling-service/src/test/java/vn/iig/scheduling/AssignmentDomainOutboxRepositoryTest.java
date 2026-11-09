package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class AssignmentDomainOutboxRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper();
    private final AssignmentDomainOutboxRepository outbox=
        new AssignmentDomainOutboxRepository(db,json);

    private com.fasterxml.jackson.databind.JsonNode payload(String body) throws Exception {
        return json.readTree(body);
    }

    private void stagedReceipt(String actor,String operation,String key) {
        when(db.query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
                any(RowMapper.class),eq(actor),eq(operation),eq(key)))
            .thenReturn(List.of("STAGED"));
    }

    @Test void emitsOnlyFromAnExistingStagedCommandAndStoresJsonAtomically()
            throws Exception {
        stagedReceipt("cm001","ASSIGN_STAFF","cmd-1");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            anyString(),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1"),
            eq("STAFF"),eq("shift-1"),eq(1L),eq("UPSERT"),anyString(),anyString()))
            .thenReturn(1);

        String id=outbox.append("cm001","ASSIGN_STAFF","cmd-1",
            "STAFF","shift-1",1,"UPSERT",payload("{\"id\":\"shift-1\",\"status\":\"ASSIGNED\"}"));
        assertNotNull(id);
        assertFalse(id.isBlank());
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            eq(id),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1"),
            eq("STAFF"),eq("shift-1"),eq(1L),eq("UPSERT"),
            contains("shift-1"),matches("[a-f0-9]{64}"));
    }

    @Test void refusesToEmitFromRejectedOrMissingCommand() throws Exception {
        when(db.query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
            any(RowMapper.class),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-2")))
            .thenReturn(List.of("REJECTED"));
        var ex=assertThrows(ResponseStatusException.class,
            ()->outbox.append("cm001","ASSIGN_STAFF","cmd-2",
                "STAFF","shift-2",1,"UPSERT",payload("{}")));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            any(Object[].class));
    }

    @Test void duplicateRevisionWithDifferentPayloadFailsClosed() throws Exception {
        stagedReceipt("cm001","ASSIGN_STAFF","cmd-3");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            anyString(),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-3"),
            eq("STAFF"),eq("shift-3"),eq(4L),eq("UPSERT"),anyString(),anyString()))
            .thenReturn(0);
        when(db.query(startsWith("SELECT event_id,actor_username,operation,request_key"),
            any(RowMapper.class),eq("STAFF"),eq("shift-3"),eq(4L)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,
                ()->outbox.append("cm001","ASSIGN_STAFF","cmd-3",
                    "STAFF","shift-3",4,"UPSERT",payload("{\"status\":\"CHANGED\"}")))
                .getStatusCode());
    }

    @Test void exactDigestAckCannotMarkWrongRevisionDelivered() {
        String hash="a".repeat(64);
        when(db.update(startsWith("UPDATE scheduling_assignment_domain_outbox SET delivered_at"),
            eq("event-1"),eq(hash))).thenReturn(1);
        assertTrue(outbox.acknowledge("event-1",hash));
        verify(db).update(contains("payload_hash=?"),eq("event-1"),eq(hash));
        assertFalse(outbox.acknowledge("event-2",hash));
    }

    @Test void retriesRetainFailedMessagesInsteadOfDroppingThem() {
        outbox.retryLater("event-1","Core HTTP 503");
        verify(db).update(contains("attempt_count=attempt_count+1"),
            eq("Core HTTP 503"),eq("event-1"));
    }

    @Test void rejectsUnboundedOutboxBatch() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->outbox.pending(501))
                .getStatusCode());
        verifyNoInteractions(db);
    }
}

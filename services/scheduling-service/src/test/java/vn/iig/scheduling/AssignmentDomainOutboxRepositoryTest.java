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

    @SuppressWarnings({"rawtypes","unchecked"})
    private void existingRevision(String actor,String operation,String key,
                                  String source,String scheduleId,long revision,
                                  String hash,String kind,String eventId) throws Exception {
        when(db.query(startsWith("SELECT event_id,actor_username,operation,request_key"),
                any(RowMapper.class),eq(source),eq(scheduleId),eq(revision)))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(eventId);
                when(rs.getString(2)).thenReturn(actor);
                when(rs.getString(3)).thenReturn(operation);
                when(rs.getString(4)).thenReturn(key);
                when(rs.getString(5)).thenReturn(kind);
                when(rs.getString(6)).thenReturn(hash);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void duplicateRevisionWithSameDigestReturnsOriginalEventId()
            throws Exception {
        stagedReceipt("cm001","ASSIGN_STAFF","cmd-retry");
        var body=payload("{\"employeeId\":\"cs-001\",\"status\":\"ASSIGNED\"}");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            anyString(),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-retry"),
            eq("STAFF"),eq("shift-11"),eq(2L),eq("UPSERT"),anyString(),anyString()))
            .thenReturn(1,0);
        String first=outbox.append("cm001","ASSIGN_STAFF","cmd-retry",
            "STAFF","shift-11",2,"UPSERT",body);
        // Read the hash from the original persisted insert arguments.
        var captor=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            eq(first),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-retry"),
            eq("STAFF"),eq("shift-11"),eq(2L),eq("UPSERT"),
            anyString(),captor.capture());
        existingRevision("cm001","ASSIGN_STAFF","cmd-retry",
            "STAFF","shift-11",2,captor.getValue(),"UPSERT",first);
        String retry=outbox.append("cm001","ASSIGN_STAFF","cmd-retry",
            "STAFF","shift-11",2,"UPSERT",
            payload("{\"status\":\"ASSIGNED\",\"employeeId\":\"cs-001\"}"));
        assertEquals(first,retry);
    }

    @Test void duplicateRevisionWithDifferentDigestNeverOverwritesPreviousEvent()
            throws Exception {
        stagedReceipt("cm001","ASSIGN_STAFF","cmd-change");
        when(db.update(startsWith("INSERT INTO scheduling_assignment_domain_outbox"),
            anyString(),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-change"),
            eq("STAFF"),eq("shift-12"),eq(2L),eq("UPSERT"),anyString(),anyString()))
            .thenReturn(0);
        existingRevision("cm001","ASSIGN_STAFF","cmd-change",
            "STAFF","shift-12",2,"f".repeat(64),"UPSERT","event-prior");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->outbox.append("cm001","ASSIGN_STAFF","cmd-change",
                    "STAFF","shift-12",2,"UPSERT",
                    payload("{\"status\":\"CANCELLED\"}")))
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

    @Test void deliveryQueueNeverSelectsStagedOrCompensatingCommands() {
        when(db.query(contains("AND inbox.state='PENDING_REFERENCE'"),
            any(RowMapper.class),eq(50))).thenReturn(List.of());
        assertEquals(List.of(),outbox.pending(50));
        verify(db).query(contains("JOIN scheduling_assignment_command_inbox inbox"),
            any(RowMapper.class),eq(50));
    }

    @Test void recoveryCannotReadEventsAlreadyAcknowledgedOrMissing() {
        when(db.query(contains("WHERE event_id=? AND delivered_at IS NULL"),
            any(RowMapper.class),eq("event-unknown"))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->outbox.unresolvedEvent("event-unknown")).getStatusCode());
        verify(db).query(contains("delivered_at IS NULL"),
            any(RowMapper.class),eq("event-unknown"));
    }

    @Test void invalidRecoveryIdDoesNotQueryTheAssignmentDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->outbox.unresolvedEvent("")).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void rejectsUnboundedOutboxBatch() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,()->outbox.pending(501))
                .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void recoveryQueueSelectsOnlyExhaustedCommandsAndNoDeliveredEvent() {
        when(db.query(contains("inbox.state='COMPENSATING'"),
            any(RowMapper.class),eq(25))).thenReturn(List.of());
        assertEquals(List.of(),outbox.awaitingVerifiedRecovery(25));
        verify(db).query(argThat(sql -> sql.contains("o.delivered_at IS NULL") &&
            sql.contains("JOIN scheduling_assignment_command_inbox inbox")),
            any(RowMapper.class),eq(25));
    }

    @Test void unresolvedCoreReferenceRecordsBoundedRetryWithoutBusinessWrite() {
        var event=new AssignmentDomainOutboxRepository.Event(
            "evt-1","tc001","ASSIGN_TEACHER","key-1","TEACHING",
            "teaching-1",1,"UPSERT","{}","a".repeat(64),10);
        when(db.queryForObject(contains("FOR UPDATE OF o"),eq(Boolean.class),
            eq("evt-1"))).thenReturn(true);
        outbox.recordVerifiedRecoveryFailure(event,
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        verify(db).update(
            argThat(sql -> sql.contains("failure_count+1>=8") &&
                sql.contains("OPERATOR_REVIEW") &&
                sql.contains("ON CONFLICT(event_id)")),
            eq("evt-1"),eq("ResponseStatusException"));
        verify(db,never()).update(startsWith("UPDATE scheduling_assignment_domain_outbox SET delivered_at"),
            any(Object[].class));
    }

    @Test void confirmedCoreEventNeverEntersOperatorQuarantine() {
        var event=new AssignmentDomainOutboxRepository.Event(
            "evt-1","tc001","ASSIGN_TEACHER","key-1","TEACHING",
            "teaching-1",1,"UPSERT","{}","a".repeat(64),10);
        when(db.queryForObject(contains("FOR UPDATE OF o"),eq(Boolean.class),
            eq("evt-1"))).thenReturn(false);
        outbox.recordVerifiedRecoveryFailure(event,
            new ResponseStatusException(HttpStatus.CONFLICT));
        verify(db,never()).update(startsWith("INSERT INTO scheduling_assignment_recovery_attempts"),
            any(Object[].class));
    }

    @Test void recoveryQueueCannotScanUnboundedSourceOrWriteRows() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->outbox.awaitingVerifiedRecovery(51)).getStatusCode());
        verifyNoInteractions(db);
    }

}

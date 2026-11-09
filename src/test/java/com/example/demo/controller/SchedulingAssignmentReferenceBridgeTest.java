package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAssignmentReferenceBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final SchedulingAssignmentReferenceBridge bridge=
        new SchedulingAssignmentReferenceBridge(db,json,"trusted-core-key");

    private SchedulingAssignmentReferenceBridge.Event event(String payload,
                                                              long revision)
            throws Exception {
        String canonical=json.writeValueAsString(
            json.convertValue(json.readTree(payload),Object.class));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        return new SchedulingAssignmentReferenceBridge.Event(
            "event-1","cm001","ASSIGN_STAFF","req-1","STAFF",
            "shift-1",revision,"UPSERT",payload,hash);
    }

    private SchedulingAssignmentReferenceBridge.Event valid() throws Exception {
        return event("{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"FRONT_DESK\"," +
            "\"status\":\"ASSIGNED\",\"date\":\"2027-02-11\"," +
            "\"startTime\":\"09:00\",\"endTime\":\"11:00\"}",1);
    }

    @Test void rejectsMissingServiceKeyBeforeInspectingDatabase() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,()->bridge.apply(null,valid()))
                .getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void rejectsTeachingAndClassLinkedStaffWithoutTouchingCore() throws Exception {
        var illegal=event("{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"CLASS_SUPPORT\"," +
            "\"classId\":\"class-1\",\"status\":\"ASSIGNED\"," +
            "\"date\":\"2027-02-11\",\"startTime\":\"09:00\"," +
            "\"endTime\":\"11:00\"}",1);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",illegal)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingRoleFailsAsBadRequestNotNullPointerException() throws Exception {
        var missing=event("{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"workType\":\"FRONT_DESK\",\"status\":\"ASSIGNED\"," +
            "\"date\":\"2027-02-11\",\"startTime\":\"09:00\"," +
            "\"endTime\":\"11:00\"}",1);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",missing)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void digestMismatchIsRejectedBeforeDatabaseWrite() throws Exception {
        var correct=valid();
        var corrupted=new SchedulingAssignmentReferenceBridge.Event(
            correct.eventId(),correct.actorUsername(),correct.operation(),
            correct.requestKey(),correct.sourceType(),correct.scheduleId(),
            correct.revision(),correct.eventKind(),
            correct.payloadJson(),"f".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",corrupted)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void cannotProjectWhileCoreIsStillAuthoritative() throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("CORE");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",valid())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void appliesStandaloneShiftAndItsReferenceReceiptInOneTransaction()
            throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.query(startsWith("SELECT last_revision,last_event_id"),
                any(RowMapper.class),eq("STAFF"),eq("shift-1")))
            .thenReturn(List.of());
        when(db.queryForObject(startsWith("SELECT COALESCE(max(version),0)"),
            eq(Long.class),eq("STAFF"),eq("shift-1"))).thenReturn(0L);

        var result=bridge.apply("trusted-core-key",valid());
        assertEquals("APPLIED",result.outcome());
        verify(db).queryForObject(contains("set_config('iig.assignment_reference_projection'"),
            eq(String.class));
        verify(db).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_reference_receipts"),
            any(Object[].class));
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_outbox"),
            eq("STAFF"),eq("shift-1"),eq(1L));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void existingVersion(long revision,String eventId,String hash) throws Exception {
        when(db.query(startsWith("SELECT last_revision,last_event_id"),
                any(RowMapper.class),eq("STAFF"),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(revision);
                when(rs.getString(2)).thenReturn(eventId);
                when(rs.getString(3)).thenReturn(hash);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void replayedAlreadyCommittedEventNeverWritesAgain() throws Exception {
        var event=valid();
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(event.revision(),event.eventId(),event.payloadHash());
        var response=bridge.apply("trusted-core-key",event);
        assertEquals("ALREADY_APPLIED",response.outcome());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void sameRevisionWithChangedDigestIsRejected() throws Exception {
        var event=valid();
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"event-other","0".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",event)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

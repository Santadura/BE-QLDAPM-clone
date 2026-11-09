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

    @org.junit.jupiter.api.BeforeEach
    void admittedCoreWriterClaim() {
        when(db.query(startsWith("SELECT status FROM scheduling_assignment_writer_claims"),
            any(RowMapper.class),eq("cm001"),eq("cm-001"),
            anyString(),anyString())).thenReturn(List.of("PENDING"));
        when(db.update(startsWith("UPDATE scheduling_assignment_writer_claims"),
            eq("cm001"),eq("cm-001"),anyString(),anyString())).thenReturn(1);
    }

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
            "\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"assignedBy\":\"cm-001\"}",1);
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
            "\"endTime\":\"11:00\",\"assignedBy\":\"cm-001\"}",1);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",illegal)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void missingRoleFailsAsBadRequestNotNullPointerException() throws Exception {
        var missing=event("{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"workType\":\"FRONT_DESK\",\"status\":\"ASSIGNED\"," +
            "\"date\":\"2027-02-11\",\"startTime\":\"09:00\"," +
            "\"endTime\":\"11:00\",\"assignedBy\":\"cm-001\"}",1);
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

    @Test void independentCoreReferenceIsRejectedWithoutAWriterClaim() throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.query(startsWith("SELECT status FROM scheduling_assignment_writer_claims"),
            any(RowMapper.class),eq("cm001"),eq("cm-001"),
            anyString(),anyString())).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",valid())).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
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

        when(db.queryForObject(contains("AND upper(r.role_name)=?"),
            eq(Long.class),eq("cs-001"),eq("CS"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)='CM'"),
            eq(Long.class),eq("cm-001"),eq("cm001"))).thenReturn(1L);
        when(db.update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class))).thenReturn(1);
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

    @Test void cannotOverwriteAnExistingLegacyCoreShiftWithoutMigratedReceipt()
            throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.queryForObject(startsWith("SELECT count(*) FROM staff_schedules"),
            eq(Long.class),eq("shift-1"))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",valid())).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
    }

    @Test void missingReferenceRevisionCannotSilentlySkipACommittedEvent()
            throws Exception {
        var event=event("{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"FRONT_DESK\"," +
            "\"status\":\"ASSIGNED\",\"date\":\"2027-02-11\"," +
            "\"startTime\":\"09:00\",\"endTime\":\"11:00\",\"assignedBy\":\"cm-001\"}",3);
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"event-old","f".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",event)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void committedShift(String employeeId,String classId,String workplaceId,
                                String status) throws Exception {
        when(db.query(startsWith("SELECT employee_id,class_id,workplace_id"),
                any(RowMapper.class),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(employeeId);
                when(rs.getString(2)).thenReturn(classId);
                when(rs.getString(3)).thenReturn(workplaceId);
                when(rs.getObject(4,java.time.LocalDate.class))
                    .thenReturn(java.time.LocalDate.of(2027,2,11));
                when(rs.getObject(5,java.time.LocalTime.class))
                    .thenReturn(java.time.LocalTime.of(9,0));
                when(rs.getObject(6,java.time.LocalTime.class))
                    .thenReturn(java.time.LocalTime.of(11,0));
                when(rs.getString(7)).thenReturn("FRONT_DESK");
                when(rs.getString(8)).thenReturn(status);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private SchedulingAssignmentReferenceBridge.Event cancelEvent()
            throws Exception {
        String payload="{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"FRONT_DESK\"," +
            "\"status\":\"CANCELLED\",\"date\":\"2027-02-11\"," +
            "\"startTime\":\"09:00\",\"endTime\":\"11:00\"," +
            "\"assignedBy\":\"cm-001\"}";
        var original=event(payload,2);
        return new SchedulingAssignmentReferenceBridge.Event(
            original.eventId(),original.actorUsername(),"CANCEL_STAFF",
            original.requestKey(),original.sourceType(),original.scheduleId(),
            original.revision(),original.eventKind(),original.payloadJson(),
            original.payloadHash());
    }

    private SchedulingAssignmentReferenceBridge.Event editEvent() throws Exception {
        String payload="{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"OPERATIONS_SUPPORT\"," +
            "\"status\":\"ASSIGNED\",\"date\":\"2027-02-12\"," +
            "\"startTime\":\"13:00\",\"endTime\":\"15:00\"," +
            "\"workplaceId\":\"center-02\",\"assignedBy\":\"cm-001\"}";
        var original=event(payload,2);
        return new SchedulingAssignmentReferenceBridge.Event(
            original.eventId(),original.actorUsername(),"EDIT_STAFF",
            original.requestKey(),original.sourceType(),original.scheduleId(),
            original.revision(),original.eventKind(),original.payloadJson(),
            original.payloadHash());
    }

    @Test void editMayChangeTimeAndLocationButNotWorkerOrClass() throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"create-event","a".repeat(64));
        committedShift("cs-001",null,null,"ASSIGNED");
        when(db.queryForObject(startsWith("SELECT COALESCE(max(version),0)"),
            eq(Long.class),eq("STAFF"),eq("shift-1"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)=?"),
            eq(Long.class),eq("cs-001"),eq("CS"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)='CM'"),
            eq(Long.class),eq("cm-001"),eq("cm001"))).thenReturn(1L);
        when(db.update(startsWith("UPDATE staff_schedules SET workplace_id="),
            eq("center-02"),eq(java.time.LocalDate.of(2027,2,12)),
            eq(java.time.LocalTime.of(13,0)),eq(java.time.LocalTime.of(15,0)),
            eq("OPERATIONS_SUPPORT"),eq("cm-001"),eq("shift-1"),eq("cs-001")))
            .thenReturn(1);
        var receipt=bridge.apply("trusted-core-key",editEvent());
        assertEquals("APPLIED",receipt.outcome());
        assertEquals(2,receipt.revision());
        verify(db).update(startsWith("UPDATE staff_schedules SET workplace_id="),
            any(Object[].class));
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
    }

    @Test void editCannotTurnCoreClassSupportIntoStandaloneStaff() throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"create-event","a".repeat(64));
        committedShift("cs-001","class-1",null,"ASSIGNED");
        when(db.queryForObject(startsWith("SELECT COALESCE(max(version),0)"),
            eq(Long.class),eq("STAFF"),eq("shift-1"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)=?"),
            eq(Long.class),eq("cs-001"),eq("CS"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)='CM'"),
            eq(Long.class),eq("cm-001"),eq("cm001"))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",editEvent())).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE staff_schedules"),
            any(Object[].class));
    }

    @Test void cancellationOnlyChangesStatusAndAssigningManager() throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"create-event","a".repeat(64));
        committedShift("cs-001",null,null,"ASSIGNED");
        when(db.queryForObject(startsWith("SELECT COALESCE(max(version),0)"),
            eq(Long.class),eq("STAFF"),eq("shift-1"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)='CM'"),
            eq(Long.class),eq("cm-001"),eq("cm001"))).thenReturn(1L);
        when(db.update(startsWith("UPDATE staff_schedules"),
            eq("cm-001"),eq("shift-1"))).thenReturn(1);
        var result=bridge.apply("trusted-core-key",cancelEvent());
        assertEquals("APPLIED",result.outcome());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
        verify(db).update(startsWith("UPDATE staff_schedules"),
            eq("cm-001"),eq("shift-1"));
    }

    @Test void cancellationCannotRewriteEmployeeOrClassLinkedRow()
            throws Exception {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(1,"create-event","a".repeat(64));
        committedShift("cs-001","class-1",null,"ASSIGNED");
        when(db.queryForObject(startsWith("SELECT COALESCE(max(version),0)"),
            eq(Long.class),eq("STAFF"),eq("shift-1"))).thenReturn(1L);
        when(db.queryForObject(contains("AND upper(r.role_name)='CM'"),
            eq(Long.class),eq("cm-001"),eq("cm001"))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",cancelEvent())).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE staff_schedules"),
            any(Object[].class));
    }

    @Test void obsoleteReferenceRevisionIsNotAcknowledgedWithoutAnExactReceipt()
            throws Exception {
        var oldEvent=valid();
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        existingVersion(2,"event-2","f".repeat(64));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",oldEvent)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
    }

    @Test void cancellationCannotCreateAReferenceWithoutAnOriginalAssignment()
            throws Exception {
        String payload="{\"id\":\"shift-1\",\"employeeId\":\"cs-001\"," +
            "\"staffRole\":\"CS\",\"workType\":\"FRONT_DESK\"," +
            "\"status\":\"CANCELLED\",\"date\":\"2027-02-11\"," +
            "\"startTime\":\"09:00\",\"endTime\":\"11:00\"," +
            "\"assignedBy\":\"cm-001\"}";
        var base=event(payload,1);
        var cancel=new SchedulingAssignmentReferenceBridge.Event(
            base.eventId(),base.actorUsername(),"CANCEL_STAFF",base.requestKey(),
            base.sourceType(),base.scheduleId(),base.revision(),base.eventKind(),
            base.payloadJson(),base.payloadHash());
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.apply("trusted-core-key",cancel)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO staff_schedules"),
            any(Object[].class));
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

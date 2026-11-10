package com.example.demo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.controller.SchedulingAssignmentReferenceBridge.Event;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class SchedulingTeacherReferenceBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final SchedulingTeacherReferenceBridge bridge=
        new SchedulingTeacherReferenceBridge(db,json,"private-scheduling-key");

    private final String payload="""
        {"id":"teach-1","teacherId":"teacher-1","classId":"class-1",
        "sessionId":"lesson-1","assignedBy":"tc-1","academicLeaseId":"lease-1",
        "status":"ASSIGNED","classVersion":3,"sessionVersion":7,
        "date":"2027-02-11","startTime":"09:00","endTime":"11:00"}""";
    private Event event() throws Exception {
        String canonical=json.writeValueAsString(
            json.convertValue(json.readTree(payload),Object.class));
        return new Event("event-teach-1","tc001","ASSIGN_TEACHER","command-1",
            "TEACHING","teach-1",1,"UPSERT",payload,
            SchedulingTeacherReferenceBridge.digest(canonical));
    }
    private void mode() {
        when(db.query(startsWith("SELECT writer_generation"),
            any(RowMapper.class))).thenReturn(List.of(3L));
    }
    private void noPrior() {
        when(db.query(startsWith("SELECT last_revision,last_event_id"),
            any(RowMapper.class),eq("teach-1"))).thenReturn(List.of());
    }
    private void tcAndTeacherLive() {
        when(db.queryForObject(contains("u.role_id='TC'"),
            eq(Long.class),eq("tc-1"),eq("tc001"))).thenReturn(1L);
        when(db.queryForObject(contains("u.role_id='TEACHER'"),
            eq(Long.class),eq("teacher-1"))).thenReturn(1L);
        when(db.query(startsWith("SELECT status FROM scheduling_assignment_writer_claims"),
            any(RowMapper.class),eq("tc001"),eq("command-1"),eq("tc-1")))
            .thenReturn(List.of("PENDING"));
    }
    private void plannedLease() {
        when(db.query(startsWith("SELECT l.lease_id FROM scheduling_academic_session_leases"),
            any(RowMapper.class),eq("lease-1"),eq("class-1"),eq("lesson-1"),
            anyString(),eq(3L),eq(3L),eq(7L),
            eq(LocalDate.of(2027,2,11)),eq(LocalTime.of(9,0)),
            eq(LocalTime.of(11,0)))).thenReturn(List.of("lease-1"));
    }
    private void cleanSlot() {
        when(db.queryForObject(startsWith("SELECT count(*) FROM teaching_schedules"),
            eq(Long.class),any(Object[].class))).thenReturn(0L);
    }
    private void prior(Event e) throws Exception {
        when(db.query(startsWith("SELECT last_revision,last_event_id"),
            any(RowMapper.class),eq("teach-1"))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(1L);
                when(rs.getString(2)).thenReturn(e.eventId());
                when(rs.getString(3)).thenReturn(e.payloadHash());
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void secretIsMandatoryBeforeAnyCoreDatabaseQuery() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->bridge.apply(null,event())).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void aStaffEventCannotBeForgedAsTeacherReference() throws Exception {
        var e=event();
        var invalid=new Event(e.eventId(),e.actorUsername(),e.operation(),e.requestKey(),
            "STAFF",e.scheduleId(),e.revision(),e.eventKind(),e.payloadJson(),e.payloadHash());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",invalid)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void aChangedEventPayloadWithSameHashIsNotAcknowledged() throws Exception {
        var e=event();
        var changed=new Event(e.eventId(),e.actorUsername(),e.operation(),e.requestKey(),
            e.sourceType(),e.scheduleId(),e.revision(),e.eventKind(),
            e.payloadJson().replace("teacher-1","teacher-2"),e.payloadHash());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",changed)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void coreTeacherWriterMustBeQuiescedBeforeAnyReference() throws Exception {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",event())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void newTeacherWriteRejectsARevokedTc() throws Exception {
        mode();noPrior();
        when(db.queryForObject(contains("u.role_id='TC'"),
            eq(Long.class),eq("tc-1"),eq("tc001"))).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",event())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void newTeacherWriteRejectsMissingLiveAcademicLease() throws Exception {
        mode();noPrior();tcAndTeacherLive();
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",event())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void newTeacherWriteRejectsExistingClassOrTeacherOverlap() throws Exception {
        mode();noPrior();tcAndTeacherLive();plannedLease();
        when(db.queryForObject(startsWith("SELECT count(*) FROM teaching_schedules"),
            eq(Long.class),any(Object[].class))).thenReturn(0L,1L,0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",event())).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO teaching_schedules"),
            any(Object[].class));
    }

    @Test void referenceCreatesTeacherAndImmutableReceiptsInSameTransaction()
            throws Exception {
        mode();noPrior();tcAndTeacherLive();plannedLease();cleanSlot();
        when(db.update(startsWith("INSERT INTO teaching_schedules"),
            any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("UPDATE scheduling_assignment_writer_claims"),
            any(Object[].class))).thenReturn(1);
        var response=bridge.apply("private-scheduling-key",event());
        assertEquals("APPLIED",response.outcome());
        var order=inOrder(db);
        order.verify(db).update(startsWith("INSERT INTO teaching_schedules"),
            any(Object[].class));
        order.verify(db).update(startsWith("INSERT INTO scheduling_assignment_reference_receipts"),
            any(Object[].class));
        order.verify(db).update(startsWith("INSERT INTO scheduling_assignment_reference_event_receipts"),
            any(Object[].class));
        order.verify(db).update(startsWith("INSERT INTO scheduling_assignment_outbox"),
            any(Object[].class));
        order.verify(db).update(startsWith("UPDATE scheduling_assignment_writer_claims"),
            any(Object[].class));
    }

    @Test void identicalReplaySucceedsAfterTcRevocationAndAcademicLeaseExpiry()
            throws Exception {
        var e=event();
        mode();prior(e);
        when(db.queryForObject(startsWith(
            "SELECT count(*) FROM scheduling_assignment_reference_event_receipts"),
            eq(Long.class),eq("teach-1"),eq("event-teach-1"),
            eq(e.payloadHash()),eq("tc001"),eq("command-1"))).thenReturn(1L);
        when(db.queryForObject(startsWith("SELECT count(*) FROM teaching_schedules"),
            eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(db.queryForObject(startsWith(
            "SELECT count(*) FROM scheduling_assignment_writer_claims"),
            eq(Long.class),eq("tc001"),eq("command-1"),eq("tc-1")))
            .thenReturn(1L);
        var report=bridge.apply("private-scheduling-key",e);
        assertEquals("ALREADY_APPLIED",report.outcome());
        verify(db,never()).queryForObject(contains("u.role_id='TC'"),
            eq(Long.class),any(Object[].class));
        verify(db,never()).query(startsWith("SELECT l.lease_id"),
            any(RowMapper.class),any(Object[].class));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void replayRejectsMissingCoreTeacherEvenWithValidImmutableHistory()
            throws Exception {
        var e=event();
        mode();prior(e);
        when(db.queryForObject(startsWith(
            "SELECT count(*) FROM scheduling_assignment_reference_event_receipts"),
            eq(Long.class),eq("teach-1"),eq("event-teach-1"),
            eq(e.payloadHash()),eq("tc001"),eq("command-1"))).thenReturn(1L);
        when(db.queryForObject(startsWith("SELECT count(*) FROM teaching_schedules"),
            eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(db.queryForObject(startsWith(
            "SELECT count(*) FROM scheduling_assignment_writer_claims"),
            eq(Long.class),eq("tc001"),eq("command-1"),eq("tc-1")))
            .thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",e)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void mismatchOfImmutableReceiptBlocksReplayBeforeAnyMutation()
            throws Exception {
        var e=event();
        mode();prior(e);
        when(db.queryForObject(startsWith(
            "SELECT count(*) FROM scheduling_assignment_reference_event_receipts"),
            eq(Long.class),any(Object[].class))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.apply("private-scheduling-key",e)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

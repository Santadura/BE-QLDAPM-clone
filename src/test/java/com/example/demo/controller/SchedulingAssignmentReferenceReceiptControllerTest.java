package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAssignmentReferenceReceiptControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAssignmentReferenceReceiptController controller=
        new SchedulingAssignmentReferenceReceiptController(db,"private-reference-key");

    @Test void keyAndInputValidationPrecedeAnyDatabaseAccess() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookup("wrong","STAFF","shift-1")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookup("private-reference-key","UNKNOWN","shift-1"))
                .getStatusCode());
        verifyNoInteractions(db);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void readOnlyCoreReceiptReturnsExactCommittedIdentity() throws Exception {
        when(db.query(startsWith("SELECT last_event_id,last_revision,payload_hash"),
                any(RowMapper.class),eq("STAFF"),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("event-2");
                when(rs.getLong(2)).thenReturn(2L);
                when(rs.getString(3)).thenReturn("a".repeat(64));
                return List.of(mapper.mapRow(rs,0));
            });
        var receipt=controller.lookup("private-reference-key","STAFF","shift-1");
        assertEquals("event-2",receipt.eventId());
        assertEquals(2,receipt.revision());
        assertEquals("a".repeat(64),receipt.payloadHash());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void immutableEventReceiptCanBeReadAfterLaterScheduleRevisions()
            throws Exception {
        when(db.query(startsWith("SELECT event_id,revision,payload_hash"),
                any(RowMapper.class),eq("event-1"),eq("STAFF"),eq("shift-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("event-1");
                when(rs.getLong(2)).thenReturn(1L);
                when(rs.getString(3)).thenReturn("a".repeat(64));
                return List.of(mapper.mapRow(rs,0));
            });
        var receipt=controller.lookupEvent(
            "private-reference-key","event-1","STAFF","shift-1");
        assertEquals("event-1",receipt.eventId());
        assertEquals(1L,receipt.revision());
        assertEquals("a".repeat(64),receipt.payloadHash());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void teacherHistoryRequiresCoreRowAndConfirmedWriterClaim() throws Exception {
        when(db.query(startsWith("SELECT event_id,revision,payload_hash"),
                any(RowMapper.class),eq("teacher-event-1"),eq("TEACHING"),eq("teacher-slot-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("teacher-event-1");
                when(rs.getLong(2)).thenReturn(1L);
                when(rs.getString(3)).thenReturn("a".repeat(64));
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.query(startsWith("SELECT t.teacher_id,t.class_id,t.date"),
            any(RowMapper.class),eq("teacher-event-1"),eq("teacher-slot-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("teacher-1");
                when(rs.getString(2)).thenReturn("class-1");
                when(rs.getObject(3,LocalDate.class)).thenReturn(LocalDate.of(2027,2,11));
                when(rs.getObject(4,LocalTime.class)).thenReturn(LocalTime.of(9,0));
                when(rs.getObject(5,LocalTime.class)).thenReturn(LocalTime.of(11,0));
                when(rs.getString(6)).thenReturn("ASSIGNED");
                when(rs.getString(7)).thenReturn("tc-1");
                return List.of(mapper.mapRow(rs,0));
            }).thenReturn(List.of());
        var verified=controller.lookupEvent(
            "private-reference-key","teacher-event-1","TEACHING","teacher-slot-1");
        assertEquals(1L,verified.revision());
        assertEquals("teacher-1",verified.teacherId());
        assertEquals("class-1",verified.classId());
        assertEquals(LocalTime.of(9,0),verified.startTime());
        assertEquals("tc-1",verified.assignedBy());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.lookupEvent("private-reference-key",
                "teacher-event-1","TEACHING","teacher-slot-1")).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void eventLookupRequiresServiceKeyAndExactScheduleIdentity() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookupEvent(null,"event-1","STAFF","shift-1"))
                .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookupEvent("private-reference-key",
                    "","STAFF","shift-1")).getStatusCode());
        when(db.query(startsWith("SELECT event_id,revision,payload_hash"),
            any(RowMapper.class),eq("event-1"),eq("STAFF"),eq("wrong-shift")))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookupEvent("private-reference-key",
                    "event-1","STAFF","wrong-shift")).getStatusCode());
    }

    @Test void missingReceiptFailsClosedRatherThanInventingAnAck() {
        when(db.query(startsWith("SELECT last_event_id,last_revision,payload_hash"),
            any(RowMapper.class),eq("STAFF"),eq("shift-1"))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookup("private-reference-key","STAFF","shift-1"))
                .getStatusCode());
    }
}

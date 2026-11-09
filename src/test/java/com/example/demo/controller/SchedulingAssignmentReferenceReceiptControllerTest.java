package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
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

    @Test void missingReceiptFailsClosedRatherThanInventingAnAck() {
        when(db.query(startsWith("SELECT last_event_id,last_revision,payload_hash"),
            any(RowMapper.class),eq("STAFF"),eq("shift-1"))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                ()->controller.lookup("private-reference-key","STAFF","shift-1"))
                .getStatusCode());
    }
}

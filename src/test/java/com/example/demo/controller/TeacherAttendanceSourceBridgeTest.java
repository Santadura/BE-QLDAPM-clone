package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class TeacherAttendanceSourceBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final TeacherAttendanceSourceBridge bridge=
        new TeacherAttendanceSourceBridge(db,"private-attendance-key");

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void missingPrivateKeyCannotReadOrConfirmEvents() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.pending(null,100)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.acknowledge("invalid",
                    new TeacherAttendanceSourceBridge.AckRequest(List.of(
                        new TeacherAttendanceSourceBridge.Ack(5,"a".repeat(64)))))
            ).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void pageSizeCannotBeUsedForUnboundedPrivateExport() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->bridge.pending("private-attendance-key",201)).getStatusCode());
        verifyNoInteractions(db);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Test void pendingFeedReturnsExactIdentityAndSourceDigest() throws Exception {
        String payload="{\"attendance_id\":\"att-1\",\"status\":\"PRESENT\"}";
        when(db.query(startsWith("SELECT event_id,teaching_schedule_id,event_kind"),
            any(RowMapper.class),eq(10))).thenAnswer(invocation->{
                var mapper=(RowMapper)invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(7L);
                when(rs.getString(2)).thenReturn("teach-1");
                when(rs.getString(3)).thenReturn("UPSERT");
                when(rs.getString(4)).thenReturn(payload);
                return List.of(mapper.mapRow(rs,0));
            });
        var event=bridge.pending("private-attendance-key",10).get(0);
        assertEquals(7L,event.eventId());
        assertEquals("teach-1",event.teachingScheduleId());
        assertEquals(payload,event.payloadJson());
        assertEquals(hash(payload),event.payloadHash());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void ackWithoutExactCoreDigestFailsClosed() throws Exception {
        when(db.query(startsWith("SELECT payload::text"),
            any(RowMapper.class),eq(7L))).thenReturn(List.of("{}"));
        var request=new TeacherAttendanceSourceBridge.AckRequest(
            List.of(new TeacherAttendanceSourceBridge.Ack(7L,"f".repeat(64))));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.acknowledge("private-attendance-key",request))
            .getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void exactlyMatchingAckCanMarkDeliveryAfterImporterCommit() throws Exception {
        when(db.query(startsWith("SELECT payload::text"),
            any(RowMapper.class),eq(7L))).thenReturn(List.of("{}"));
        var request=new TeacherAttendanceSourceBridge.AckRequest(
            List.of(new TeacherAttendanceSourceBridge.Ack(7L,hash("{}"))));
        assertEquals(1,bridge.acknowledge(
            "private-attendance-key",request).confirmed());
        verify(db).update(startsWith("UPDATE teacher_attendance_source_outbox"),
            eq(7L));
    }

    @Test void missingCoreEventCannotBeAcknowledged() throws Exception {
        when(db.query(startsWith("SELECT payload::text"),
            any(RowMapper.class),eq(999L))).thenReturn(List.of());
        var request=new TeacherAttendanceSourceBridge.AckRequest(
            List.of(new TeacherAttendanceSourceBridge.Ack(999L,hash("{}"))));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.acknowledge("private-attendance-key",request))
            .getStatusCode());
    }
}

package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionSourceBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicSessionSourceBridge bridge=
        new AcademicSessionSourceBridge(db,"private-key");
    @Test void noServiceKeyCannotReadOrAcknowledgeEvents() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->bridge.pending(null,10)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.acknowledge(null,new AcademicSessionSourceBridge.Ack(
                1,"a".repeat(64)))).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void invalidAckAndCursorFailClosedBeforeSQL() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->bridge.acknowledge("private-key",
                new AcademicSessionSourceBridge.Ack(2,"garbled"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->bridge.history("private-key",-1,100)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void missingOriginalEventCannotBeAcknowledged() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.acknowledge("private-key",
                new AcademicSessionSourceBridge.Ack(77,"a".repeat(64))))
                .getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicStudentRosterSourceBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicStudentRosterSourceBridge source=
        new AcademicStudentRosterSourceBridge(db,"private-key");

    @Test void unauthorizedHistoryOrSummaryNeverTouchesStudentRows() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->source.history(null,0,100)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->source.summary("invalid")).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void unauthorizedWriterFenceCannotInspectCoreRosterMode() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->source.writerFence(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->source.writerFence("wrong")).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void unboundedHistoryOrNegativeCursorIsRejectedBeforeSQL() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->source.history("private-key",-1,100)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->source.history("private-key",0,101)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->source.history("private-key",0,0)).getStatusCode());
        verifyNoInteractions(db);
    }
}

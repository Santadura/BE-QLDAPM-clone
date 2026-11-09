package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PayrollInternalControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final PayrollInternalController bridge=
        new PayrollInternalController(db,"private-payroll-test-key");

    @Test void blocksUntrustedFactRequests() {
        assertThrows(ResponseStatusException.class,
            ()->bridge.facts("wrong-key","2026-10"));
        verifyNoInteractions(db);
    }

    @Test void blocksAnonymousActorEvenWithServiceKey() {
        assertThrows(ResponseStatusException.class,
            ()->bridge.actor("private-payroll-test-key",null));
        verifyNoInteractions(db);
    }

    @Test void rejectsMalformedMonthBeforeQuery() {
        assertThrows(ResponseStatusException.class,
            ()->bridge.facts("private-payroll-test-key","2026-99"));
        verifyNoInteractions(db);
    }

    @Test void refusesHistoryExportWithoutInternalKey() {
        assertThrows(ResponseStatusException.class,
            ()->bridge.exportHistorical(null));
        verifyNoInteractions(db);
    }
}

package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.service.StatisticsService;

class ReportingInternalControllerTest {
    private final StatisticsService statistics = mock(StatisticsService.class);
    private final ReportingInternalController api =
        new ReportingInternalController(statistics, "test-internal-secret");

    @Test void rejectsUntrustedCall() {
        assertThrows(ResponseStatusException.class,
            () -> api.statistics("wrong", "YEAR", 2026, null, null));
        verifyNoInteractions(statistics);
    }

    @Test void trustedCallReceivesFullDateParameters() {
        api.statistics("test-internal-secret", "DAY", 2026, null, LocalDate.of(2026, 11, 9));
        verify(statistics).internalStatistics("DAY", 2026, null, LocalDate.of(2026, 11, 9));
    }
}

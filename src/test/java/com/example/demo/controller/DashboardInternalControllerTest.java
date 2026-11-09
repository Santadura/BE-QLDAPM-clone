package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import com.example.demo.service.DashboardService;

class DashboardInternalControllerTest {
    private final DashboardService dashboard = mock(DashboardService.class);
    private final DashboardInternalController controller =
        new DashboardInternalController(dashboard, "test-internal-key");

    @Test void rejectsMissingOrIncorrectServiceKey() {
        assertThrows(ResponseStatusException.class,
            () -> controller.dashboard(null,"admin"));
        assertThrows(ResponseStatusException.class,
            () -> controller.dashboard("wrong","admin"));
        verifyNoInteractions(dashboard);
    }

    @Test void rejectsMalformedUsername() {
        assertThrows(ResponseStatusException.class,
            () -> controller.dashboard("test-internal-key",""));
        verifyNoInteractions(dashboard);
    }

    @Test void invokesExistingDashboardWithRequestedPrincipalOnlyWhenAuthorized() {
        controller.dashboard("test-internal-key","teacher001");
        verify(dashboard).getDashboard(argThat((Authentication actor) ->
            actor.isAuthenticated() && actor.getName().equals("teacher001")));
    }
}

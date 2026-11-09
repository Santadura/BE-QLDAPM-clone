package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SchedulingInternalControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingInternalController subject=
        new SchedulingInternalController(db,"trusted-scheduling-key");

    @Test void preventsCredentiallessAvailabilityExport() {
        assertThrows(ResponseStatusException.class,()->subject.export(null));
        verifyNoInteractions(db);
    }

    @Test void preventsUntrustedProjectionAndGuard() {
        assertThrows(ResponseStatusException.class,()->subject.project("wrong",null));
        assertThrows(ResponseStatusException.class,()->subject.guardEdit(null,null));
        verifyNoInteractions(db);
    }

    @Test void refusesUnknownActorEvenWithServiceKey() {
        assertThrows(ResponseStatusException.class,
            ()->subject.actor("trusted-scheduling-key",null));
        verifyNoInteractions(db);
    }
}

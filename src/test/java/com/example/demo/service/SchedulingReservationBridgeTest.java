package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchedulingReservationBridgeTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SchedulingReservationClient reservation=mock(SchedulingReservationClient.class);
    private final SchedulingAccess access=new SchedulingAccess(jdbc,reservation);

    @Test void delegatesToAuthoritativeAvailabilityWhenServiceEnabled() {
        when(reservation.enabled()).thenReturn(true);
        var date=java.time.LocalDate.of(2026,11,9);
        var start=java.time.LocalTime.of(18,0);
        var end=java.time.LocalTime.of(20,0);
        when(reservation.reserveIfApproved("teacher-001",date,start,end,null)).thenReturn(true);
        assertTrue(access.hasApprovedAvailability("teacher-001",date,start,end,null));
        verifyNoInteractions(jdbc);
    }

    @Test void retainsLegacyJDBCBehaviorOutsideMicroservicesMode() {
        when(reservation.enabled()).thenReturn(false);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class)))
            .thenReturn(1);
        // Core still has a fallback for the unchanged docker-compose.yml.
        assertDoesNotThrow(()->access.hasApprovedAvailability("teacher-001",
            java.time.LocalDate.of(2026,11,9),java.time.LocalTime.of(18,0),
            java.time.LocalTime.of(20,0),null));
    }
}

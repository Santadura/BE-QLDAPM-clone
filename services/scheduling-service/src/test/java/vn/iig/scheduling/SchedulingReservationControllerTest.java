package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.scheduling.SchedulingModels.*;

class SchedulingReservationControllerTest {
    private final AvailabilityRepository store=mock(AvailabilityRepository.class);
    private final SchedulingReservationController controller=
        new SchedulingReservationController(store,"trusted-reservation-key");

    @Test void nobodyCanHoldOrReleaseReservationsFromPublicInternet() {
        assertThrows(ResponseStatusException.class,
            ()->controller.reserve(null,null));
        assertThrows(ResponseStatusException.class,
            ()->controller.release("bad","old-id"));
        verifyNoInteractions(store);
    }

    @Test void refusesReservationWhenBootstrapIsIncomplete() {
        when(store.imported()).thenReturn(false);
        assertThrows(ResponseStatusException.class,
            ()->controller.reserve("trusted-reservation-key",null));
        verify(store,never()).reserve(any());
    }

    @Test void forwardsTrustedReservationAndIdempotentRelease() {
        when(store.imported()).thenReturn(true);
        var request=new ReservationRequest("teacher-001",java.time.LocalDate.of(2026,11,9),
            java.time.LocalTime.of(18,0),java.time.LocalTime.of(20,0),null);
        when(store.reserve(request)).thenReturn(new Lease(true,"reservation-1"));
        assertEquals("reservation-1",controller.reserve("trusted-reservation-key",request).reservationId());
        controller.release("trusted-reservation-key","reservation-1");
        verify(store).release("reservation-1");
    }
}

package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import vn.iig.scheduling.SchedulingModels.*;

class AvailabilityServiceTest {
    private final AvailabilityRepository store=mock(AvailabilityRepository.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AvailabilityService service=new AvailabilityService(store,core);
    private final Actor teacher=new Actor("teacher-001","teacher001","TEACHER","Teacher A");
    private final Actor tc=new Actor("emp-tc-001","tc001","TC","TC");
    private final Actor cm=new Actor("emp-cm-001","cm001","CM","CM");
    private final AvailabilityRequest recurring=new AvailabilityRequest(null,"mon","EVENING",
        LocalTime.of(18,0),LocalTime.of(20,0),"RECURRING","Cơ sở chính",null);

    @Test void teacherCanRegisterRecurringSlotButCannotReview() {
        when(core.currentActor("teacher")).thenReturn(teacher);
        when(store.imported()).thenReturn(true);
        Slot row=new Slot("slot1","teacher-001","Teacher A","TEACHER",
            null,"mon","EVENING",LocalTime.of(18,0),LocalTime.of(20,0),
            "RECURRING","Cơ sở chính",null,"PENDING",Instant.now(),null,null,1,false);
        when(store.create(eq(teacher),eq(recurring),eq("mon"),eq("RECURRING")))
            .thenReturn(row);
        assertEquals("slot1",service.create("teacher",recurring).get("id"));
        assertThrows(ResponseStatusException.class,()->
            service.review("teacher","slot1",new ReviewRequest("APPROVED")));
        verify(store,never()).review(anyString(),any(),anyString());
    }

    @Test void doesNotAcceptOvernightOrMismatchedWeekday() {
        when(core.currentActor("teacher")).thenReturn(teacher);
        when(store.imported()).thenReturn(true);
        var invalid=new AvailabilityRequest(LocalDate.of(2026,11,9),"tue",
            "EVENING",LocalTime.of(20,0),LocalTime.of(18,0),
            "WEEKLY",null,null);
        assertThrows(ResponseStatusException.class,()->service.create("teacher",invalid));
        var wrongDay=new AvailabilityRequest(LocalDate.of(2026,11,9),"tue",
            "EVENING",LocalTime.of(18,0),LocalTime.of(20,0),
            "WEEKLY",null,null);
        assertThrows(ResponseStatusException.class,()->service.create("teacher",wrongDay));
        verify(store,never()).create(any(),any(),anyString(),anyString());
    }

    @Test void coordinatorCannotInspectOrApproveCustomerServiceAvailability() {
        when(core.currentActor("tc")).thenReturn(tc);
        when(store.imported()).thenReturn(true);
        assertThrows(ResponseStatusException.class,()->
            service.list("tc","CS",null,null));
        verify(store,never()).list(any(),any(),any(),any(),any());
    }

    @Test void centerManagerCannotApproveTeacherAvailability() {
        when(core.currentActor("cm")).thenReturn(cm);
        when(store.imported()).thenReturn(true);
        var row=new Slot("slot1","teacher-001","Teacher A","TEACHER",
            null,"mon","EVENING",LocalTime.of(18,0),LocalTime.of(20,0),
            "RECURRING",null,null,"PENDING",Instant.now(),null,null,1,false);
        when(store.review("slot1",cm,"APPROVED")).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->
            service.review("cm","slot1",new ReviewRequest("APPROVED")));
    }

    @Test void firstBootImportsLegacyBeforeOpeningAPI() {
        when(store.imported()).thenReturn(false,true);
        when(core.exportLegacy()).thenReturn(List.of());
        service.syncWithCore();
        verify(store).importInitial(List.of());
        verify(core).exportLegacy();
    }

    @Test void failedProjectionNeverAcknowledgesTheOutbox() {
        when(store.imported()).thenReturn(true);
        var slot=new Slot("slot1","teacher-001","Teacher A","TEACHER",
            null,"mon","EVENING",LocalTime.of(18,0),LocalTime.of(20,0),
            "RECURRING",null,null,"APPROVED",Instant.now(),null,null,2,false);
        when(store.pending(100)).thenReturn(List.of(new AvailabilityRepository.Pending(slot,2)));
        doThrow(new IllegalStateException("Core down")).when(core).project(slot);
        service.syncWithCore();
        verify(store,never()).acknowledge(anyString(),anyLong());
    }
}

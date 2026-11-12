package vn.iig.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceNativeCommandServiceTest {
    private final AttendanceCoreClient identity=mock(AttendanceCoreClient.class);
    private final AttendanceNativeReferenceClient core=
        mock(AttendanceNativeReferenceClient.class);
    private final AttendanceNativeLedgerRepository ledger=
        mock(AttendanceNativeLedgerRepository.class);
    private final AttendanceNativeCommandService service=
        new AttendanceNativeCommandService(identity,core,ledger);
    private final ObjectMapper mapper=new ObjectMapper();
    private final String hash=AttendanceCommandService.fingerprint(
        "class-1","teach-1","PRESENT");
    private final String eventId="00000000-0000-0000-0000-000000000001";

    private AttendanceNativeReferenceClient.Facts facts() {
        return new AttendanceNativeReferenceClient.Facts(
            "class-1","teach-1","session-1",3,4,5,"support-1",6,
            "employee-1",java.time.LocalDate.of(2026,11,11),
            java.time.LocalTime.of(9,0),java.time.LocalTime.of(11,0),
            2,3,"a".repeat(64));
    }
    private AttendanceNativeReferenceClient.Claim claim() {
        return new AttendanceNativeReferenceClient.Claim(
            "11111111-1111-1111-1111-111111111111","cs001","employee-1",
            "class-1","teach-1","same-key",hash,"PRESENT",
            "a".repeat(64),2,null,null,"ATTENDANCE","OPEN");
    }

    @Test void nativeWriteRequiresIdempotencyKeyBeforeNetworkCall() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.mark("class-1","teach-1","Bearer cs","PRESENT",null))
            .getStatusCode());
        verifyNoInteractions(identity,core,ledger);
    }

    @Test void nativeWriteRequiresActiveCSBeforeNativeClaim() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.mark("class-1","teach-1","Bearer admin","PRESENT","same-key"))
            .getStatusCode());
        verifyNoInteractions(core,ledger);
    }

    @Test void independentNativeWriteOnlyReturnsAfterBothDurableCommits() throws Exception {
        when(identity.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.facts("class-1","teach-1","Bearer cs")).thenReturn(facts());
        when(core.claim("class-1","teach-1","Bearer cs","same-key",
            hash,"PRESENT","a".repeat(64),"cs001")).thenReturn(claim());
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(eventId,"PRESENT",1,false));
        var original=new AttendanceNativeLedgerRepository.Pending(
            eventId,"cs001","class-1","teach-1","same-key",1,"{}","b".repeat(64));
        when(ledger.event(eventId)).thenReturn(original);
        var response=mapper.readTree(
            "{\"id\":\"attendance-1\",\"teachingScheduleId\":\"teach-1\",\"status\":\"PRESENT\"}");
        when(core.reference(original)).thenReturn(
            new AttendanceNativeReferenceClient.Receipt(
                eventId,"teach-1",1,"b".repeat(64),"COMMITTED",response));
        assertEquals(response,service.mark(
            "class-1","teach-1","Bearer cs","PRESENT","same-key"));
        verify(ledger).stage(argThat(x->
            "ATTENDANCE".equals(x.ownerMode()) &&
            "cs001".equals(x.actorUsername()) &&
            "PRESENT".equals(x.status()) &&
            x.writerGeneration()==2));
        verify(ledger).confirm(eq(original),argThat(p->
            eventId.equals(p.path("eventId").asText()) &&
            "b".repeat(64).equals(p.path("payloadHash").asText())));
    }

    @Test void coreTimeoutAfterLocalCommitLeavesNativeOutboxPending() {
        when(identity.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.facts("class-1","teach-1","Bearer cs")).thenReturn(facts());
        when(core.claim("class-1","teach-1","Bearer cs","same-key",
            hash,"PRESENT","a".repeat(64),"cs001")).thenReturn(claim());
        when(ledger.stage(any())).thenReturn(
            new AttendanceNativeLedgerRepository.Committed(eventId,"PRESENT",1,false));
        var original=new AttendanceNativeLedgerRepository.Pending(
            eventId,"cs001","class-1","teach-1","same-key",1,"{}","b".repeat(64));
        when(ledger.event(eventId)).thenReturn(original);
        when(core.reference(original)).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"lost Core reply after commit"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.mark(
                "class-1","teach-1","Bearer cs","PRESENT","same-key")).getStatusCode());
        verify(ledger,never()).confirm(any(),any());
    }

    @Test void CoreClaimMustMatchCurrentCSFacts() {
        when(identity.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.facts("class-1","teach-1","Bearer cs")).thenReturn(facts());
        var forged=new AttendanceNativeReferenceClient.Claim(
            "11111111-1111-1111-1111-111111111111","cs001","other-employee",
            "class-1","teach-1","same-key",hash,"PRESENT",
            "a".repeat(64),2,null,null,"ATTENDANCE","OPEN");
        when(core.claim("class-1","teach-1","Bearer cs","same-key",
            hash,"PRESENT","a".repeat(64),"cs001")).thenReturn(forged);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.mark("class-1","teach-1","Bearer cs","PRESENT","same-key"))
            .getStatusCode());
        verifyNoInteractions(ledger);
    }
    @Test void completedSameKeyReplayReturnsOriginalCoreResponseWithoutNewClaim()
            throws Exception {
        when(identity.currentPrincipal("Bearer cs")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        var original=new AttendanceNativeLedgerRepository.Pending(
            eventId,"cs001","class-1","teach-1","same-key",2,
            "{\"eventId\":\""+eventId+"\"}","b".repeat(64));
        when(ledger.completedForKey("cs001","class-1","teach-1","same-key",hash))
            .thenReturn(original);
        var response=mapper.readTree(
            "{\"id\":\"attendance-1\",\"teachingScheduleId\":\"teach-1\","+
            "\"status\":\"PRESENT\"}");
        when(core.reference(original)).thenReturn(
            new AttendanceNativeReferenceClient.Receipt(
                eventId,"teach-1",2,"b".repeat(64),"COMMITTED",response));
        assertEquals(response,service.mark(
            "class-1","teach-1","Bearer cs","PRESENT","same-key"));
        verify(core).reference(original);
        verify(core,never()).facts(anyString(),anyString(),anyString());
        verify(core,never()).claim(anyString(),anyString(),anyString(),
            anyString(),anyString(),anyString(),anyString(),anyString());
        verify(ledger,never()).stage(any());
        verify(ledger,never()).confirm(any(),any());
    }

    @Test void completedReplayStillRejectsRevokedCSRoleBeforeDatabaseLookup() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.mark("class-1","teach-1","Bearer admin","PRESENT","same-key"))
            .getStatusCode());
        verifyNoInteractions(core,ledger);
    }

}

package vn.iig.attendance;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendancePayrollEvidenceControllerTest {
    private final AttendanceEventProjectionRepository projection=
        mock(AttendanceEventProjectionRepository.class);
    private final AttendanceCutoverVerifier verifier=mock(AttendanceCutoverVerifier.class);
    private final AttendanceSourceClient source=mock(AttendanceSourceClient.class);
    private final AttendancePayrollEvidenceController controller=
        new AttendancePayrollEvidenceController(projection,verifier,source,"payroll-secret");
    private final AttendanceSourceClient.WriterFence fence=
        new AttendanceSourceClient.WriterFence("CORE",3,"CORE",7);
    private final AttendanceSourceClient.ManifestSummary summary=
        new AttendanceSourceClient.ManifestSummary(1,1,1,4,9,0,0);

    private void stableParity() {
        when(source.writerFence()).thenReturn(fence);
        when(source.manifestSummary()).thenReturn(summary);
        when(verifier.verify()).thenReturn(new AttendanceCutoverVerifier.Report(
            true,"READY",1,1,0,List.of()));
    }

    @Test void distinctPayrollKeyIsRequiredBeforeAnyDbOrSourceAccess() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence(null,"teach-1")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.readiness("attendance-internal-key")).getStatusCode());
        verifyNoInteractions(source,projection,verifier);
    }

    @Test void invalidOrDuplicateScheduleInputsAreRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1,teach-1")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1,,teach-2")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1;DROP TABLE")).getStatusCode());
        verifyNoInteractions(source,projection,verifier);
    }

    @Test void stableIndependentLedgerOnlyProvidesStatusAndEventProvenance() {
        stableParity();
        var evidence=new AttendanceEventProjectionRepository.PayrollEvidence(
            "teach-1","UPSERT","PRESENT",9,"a".repeat(64));
        when(projection.payrollEvidence(List.of("teach-1"))).thenReturn(List.of(evidence));
        var response=controller.evidence("payroll-secret","teach-1");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        var data=response.getBody();
        assertEquals(9L,data.sourceRevision());
        assertEquals(4L,data.sourceEventCount());
        assertEquals(3L,data.attendanceGeneration());
        assertEquals(7L,data.schedulingGeneration());
        assertEquals(List.of(evidence),data.evidence());
        verify(verifier).verify();
        verify(source,times(2)).writerFence();
        verify(source,times(2)).manifestSummary();
    }

    @Test void emptyWorkMonthStillNeedsGlobalHistoricalParity() {
        stableParity();
        var result=controller.readiness("payroll-secret").getBody();
        assertEquals(List.of(),result.evidence());
        verifyNoInteractions(projection);
        verify(verifier).verify();
    }

    @Test void historicalPageMustBeKeyProtectedAndBounded() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.events(null,0,100)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.events("payroll-secret",-1,100)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.events("payroll-secret",0,101)).getStatusCode());
        verifyNoInteractions(source,projection,verifier);
    }

    @Test void immutableCompleteHistoryIsPaginatedWithoutFinancialOrActorData() {
        stableParity();
        var event=new AttendanceEventProjectionRepository.PayrollHistoryEvent(
            9,"teach-1","UPSERT","PRESENT","a".repeat(64));
        when(projection.payrollHistory(0,100)).thenReturn(List.of(event));
        var page=controller.events("payroll-secret",0,100).getBody();
        assertEquals(HttpStatus.OK,
            controller.events("payroll-secret",0,100).getStatusCode());
        assertEquals(List.of(event),page.events());
        assertEquals(9L,page.sourceRevision());
        assertEquals(4L,page.sourceEventCount());
        verify(projection,times(2)).payrollHistory(0,100);
    }

    @Test void historyEventWithReusedOrBackwardsIdIsRejected() {
        stableParity();
        when(projection.payrollHistory(9,100)).thenReturn(List.of(
            new AttendanceEventProjectionRepository.PayrollHistoryEvent(
                9,"teach-1","UPSERT","PRESENT","a".repeat(64))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->controller.events("payroll-secret",9,100)).getStatusCode());
    }

    @Test void tamperedHistoryRefusesToPublishPayrollEvidence() {
        when(source.writerFence()).thenReturn(fence);
        when(source.manifestSummary()).thenReturn(summary);
        when(verifier.verify()).thenReturn(new AttendanceCutoverVerifier.Report(
            false,"MISMATCH",1,1,1,List.of("teach-1")));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1")).getStatusCode());
        verifyNoInteractions(projection);
    }

    @Test void writerModeAbaOrNewSourceEventInvalidatesTheProof() {
        stableParity();
        when(source.writerFence()).thenReturn(fence,
            new AttendanceSourceClient.WriterFence("CORE",5,"CORE",7));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1")).getStatusCode());
        when(source.writerFence()).thenReturn(fence);
        when(source.manifestSummary()).thenReturn(summary,
            new AttendanceSourceClient.ManifestSummary(1,1,1,5,10,0,0));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->controller.evidence("payroll-secret","teach-1")).getStatusCode());
    }
}

package vn.iig.attendance;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendanceCutoverVerifierTest {
    private final AttendanceSourceClient source=mock(AttendanceSourceClient.class);
    private final AttendanceEventProjectionRepository projection=
        mock(AttendanceEventProjectionRepository.class);
    private final AttendanceEventRelay relay=mock(AttendanceEventRelay.class);
    private final AttendanceCutoverVerifier verifier=
        new AttendanceCutoverVerifier(source,projection,relay);
    private final AttendanceSourceClient.ManifestRow row=
        new AttendanceSourceClient.ManifestRow(7,"teach-1","UPSERT",
            "{\"teaching_schedule_id\":\"teach-1\",\"status\":\"PRESENT\"}",true);
    private final AttendanceSourceClient.ManifestSummary clean=
        new AttendanceSourceClient.ManifestSummary(1,1,1,1,7,0,0);

    private void matching() {
        when(source.manifestSummary()).thenReturn(clean);
        when(source.manifest("",250)).thenReturn(List.of(row));
        when(projection.matchesManifest(row)).thenReturn(true);
        when(projection.projectedRowCount()).thenReturn(1L);
    }

    @Test void returnsReadyOnlyForFullStableSourceAndIndependentProjection() {
        matching();
        var report=verifier.verify();
        assertTrue(report.ready());
        assertEquals("READY",report.state());
        assertEquals(1,report.sourceRows());
        assertEquals(1,report.verifiedRows());
        verify(relay).requireCaughtUp();
        verify(source,times(2)).manifestSummary();
    }

    @Test void mismatchedLocalRowBlocksCutover() {
        matching();
        when(projection.matchesManifest(row)).thenReturn(false);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertEquals("MISMATCH",report.state());
        assertTrue(report.mismatchKeys().contains("teach-1"));
    }

    @Test void sourceTableWithoutCommittedEventIsNotReady() {
        when(source.manifestSummary()).thenReturn(
            new AttendanceSourceClient.ManifestSummary(1,0,0,0,0,0,0));
        when(projection.projectedRowCount()).thenReturn(0L);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("CORE_UNTRACKED_LIVE_ROWS"));
        verify(source,never()).manifest(anyString(),anyInt());
    }

    @Test void changedSourceDuringManifestFailsClosed() {
        matching();
        var modified=new AttendanceSourceClient.ManifestSummary(1,1,1,2,8,1,0);
        when(source.manifestSummary()).thenReturn(clean,modified);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("SOURCE_CHANGED_DURING_VERIFICATION"));
    }

    @Test void coreRowDivergingFromLatestEventBlocksReadiness() {
        matching();
        when(source.manifestSummary()).thenReturn(
            new AttendanceSourceClient.ManifestSummary(1,1,1,1,7,0,1));
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("CORE_SOURCE_DIVERGENCE"));
    }

    @Test void ghostLocalRowBlocksCutoverEvenWithValidSourceRows() {
        matching();
        when(projection.projectedRowCount()).thenReturn(2L);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("ATTENDANCE_ORPHAN_OR_MISSING_ROWS"));
    }

    @Test void oversizedManifestOrCoreOutageNeverReturnsOptimisticReady() {
        when(source.manifestSummary()).thenReturn(
            new AttendanceSourceClient.ManifestSummary(26000,26000,26000,26000,26000,0,0));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
        verify(source,never()).manifest(anyString(),anyInt());
    }

    @Test void sourceOutageIsFailClosed() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"core offline"))
            .when(relay).requireCaughtUp();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
        verifyNoInteractions(source,projection);
    }
}

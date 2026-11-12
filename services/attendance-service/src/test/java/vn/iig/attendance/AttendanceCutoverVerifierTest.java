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
    private final AttendanceCommandInbox commands=mock(AttendanceCommandInbox.class);
    private final AttendanceNativeLedgerRepository nativeLedger=
        mock(AttendanceNativeLedgerRepository.class);
    private final AttendanceCutoverVerifier verifier=
        new AttendanceCutoverVerifier(source,projection,relay,commands,nativeLedger);
    private final AttendanceSourceClient.ManifestRow row=
        new AttendanceSourceClient.ManifestRow(7,"teach-1","UPSERT",
            "{\"teaching_schedule_id\":\"teach-1\",\"status\":\"PRESENT\"}",true);
    private final AttendanceSourceClient.Event historicalEvent=
        new AttendanceSourceClient.Event(7,"teach-1","UPSERT",
            "{\"teaching_schedule_id\":\"teach-1\",\"status\":\"PRESENT\"}","a".repeat(64));
    private final AttendanceSourceClient.ManifestSummary clean=
        new AttendanceSourceClient.ManifestSummary(1,1,1,1,7,0,0);

    private void matching() {
        when(nativeLedger.recoverySummary()).thenReturn(
            new AttendanceNativeLedgerRepository.RecoverySummary(0,0));
        when(source.manifestSummary()).thenReturn(clean);
        when(source.manifest("",250)).thenReturn(List.of(row));
        when(projection.matchesManifest(row)).thenReturn(true);
        when(projection.matchesLedgerManifest(row)).thenReturn(true);
        when(projection.projectedRowCount()).thenReturn(1L);
        when(projection.ledgerRowCount()).thenReturn(1L);
        when(projection.historicalEventCount()).thenReturn(1L);
        when(projection.historyCursor()).thenReturn(7L);
        when(source.history(0L,200)).thenReturn(List.of(historicalEvent));
        when(projection.matchesHistoricalEvent(historicalEvent)).thenReturn(true);
    }

    @Test void returnsReadyOnlyForFullStableSourceAndIndependentProjection() {
        matching();
        var report=verifier.verify();
        assertTrue(report.ready());
        assertEquals("READY",report.state());
        assertEquals(1,report.sourceRows());
        assertEquals(1,report.verifiedRows());
        verify(relay).requireCaughtUp();
        verify(relay).requireHistoryCaughtUp();
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

    @Test void normalizedLedgerDivergencePreventsCutoverReadiness() {
        matching();
        when(projection.matchesLedgerManifest(row)).thenReturn(false);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("teach-1"));
    }

    @Test void missingOrTamperedHistoricalEventPreventsReadiness() {
        matching();
        when(projection.matchesHistoricalEvent(historicalEvent)).thenReturn(false);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("ATTENDANCE_EVENT_HISTORY_MISMATCH"));
    }

    @Test void missingHistoricalReceiptBlocksCutoverEvenWithLatestRow() {
        matching();
        when(projection.historicalReceiptDivergence()).thenReturn(1L);
        var report=verifier.verify();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains(
            "INCOMPLETE_OR_DIVERGENT_ATTENDANCE_EVENT_HISTORY"));
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

    private AttendanceSourceClient.WriterFence frozen(long generation) {
        return new AttendanceSourceClient.WriterFence(
            "QUIESCED",generation,"QUIESCED",generation);
    }

    @Test void ordinaryParityIsInsufficientForFrozenCutoverPreflight() {
        when(source.writerFence()).thenReturn(
            new AttendanceSourceClient.WriterFence("CORE",1,"CORE",1));
        var report=verifier.verifyFrozen();
        assertFalse(report.ready());
        assertEquals("NOT_FROZEN",report.state());
        assertTrue(report.mismatchKeys().contains("CORE_WRITERS_NOT_QUIESCED"));
        verify(source,never()).manifestSummary();
        verifyNoInteractions(projection,relay);
    }

    @Test void stableFrozenGenerationsAndCompleteParityCanPassPreflight() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        var result=verifier.verifyFrozen();
        assertTrue(result.ready());
        assertEquals("READY",result.state());
        verify(source,times(2)).writerFence();
    }

    @Test void attendanceOrSchedulingModeAbaDuringScanIsNotSafe() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7),frozen(9));
        var report=verifier.verifyFrozen();
        assertFalse(report.ready());
        assertTrue(report.mismatchKeys().contains("WRITER_MODE_CHANGED_DURING_SCAN"));
    }

    @Test void unfrozenCoreAfterScanCannotAccidentallyPass() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7),
            new AttendanceSourceClient.WriterFence("CORE",8,"QUIESCED",7));
        assertFalse(verifier.verifyFrozen().ready());
    }

    @Test void sourceOutageIsFailClosed() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"core offline"))
            .when(relay).requireCaughtUp();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,verifier::verify).getStatusCode());
        verifyNoInteractions(source,projection);
    }
    @Test void pendingLocalReceiptBlocksFrozenCutoverBeforeCoreScan() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(commands.pendingCount()).thenReturn(1L);
        var result=verifier.verifyFrozen();
        assertFalse(result.ready());
        assertEquals("UNRESOLVED_ATTENDANCE_COMMANDS",result.state());
        assertTrue(result.mismatchKeys().contains("ATTENDANCE_PENDING_CORE_RESULT"));
        verify(source,never()).manifestSummary();
    }

    @Test void commandCreatedDuringScanBlocksFrozenOwnershipEvenIfSourceHistoryMatches() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(commands.pendingCount()).thenReturn(0L,1L);
        var result=verifier.verifyFrozen();
        assertFalse(result.ready());
        assertTrue(result.mismatchKeys().contains("ATTENDANCE_COMMAND_STARTED_DURING_SCAN"));
        verify(source,times(2)).writerFence();
    }

    @Test void independentInboxOutageCannotBeReportedAsReady() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(commands.pendingCount()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Postgres offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,verifier::verifyFrozen).getStatusCode());
        verify(source,never()).manifestSummary();
    }

    @Test void nativeUnconfirmedReferenceBlocksFrozenHandbackDespiteCompleteCoreHistory() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(nativeLedger.recoverySummary()).thenReturn(
            new AttendanceNativeLedgerRepository.RecoverySummary(1,1));
        var report=verifier.verifyFrozen();
        assertFalse(report.ready());
        assertEquals("UNRESOLVED_NATIVE_ATTENDANCE",report.state());
        assertTrue(report.mismatchKeys().contains("NATIVE_ATTENDANCE_REFERENCE_UNCONFIRMED"));
        verify(source,never()).manifestSummary();
    }

    @Test void nativeReferenceStartedDuringImmutableScanBlocksHandback() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(nativeLedger.recoverySummary()).thenReturn(
            new AttendanceNativeLedgerRepository.RecoverySummary(0,0),
            new AttendanceNativeLedgerRepository.RecoverySummary(1,0));
        var report=verifier.verifyFrozen();
        assertFalse(report.ready());
        assertEquals("UNRESOLVED_NATIVE_ATTENDANCE",report.state());
        assertTrue(report.mismatchKeys().contains(
            "NATIVE_ATTENDANCE_REFERENCE_CHANGED_DURING_SCAN"));
    }

    @Test void nativePostgresOutageCannotBeCountedAsFrozenReadiness() {
        matching();
        when(source.writerFence()).thenReturn(frozen(7));
        when(nativeLedger.recoverySummary()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"native store offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,verifier::verifyFrozen)
            .getStatusCode());
        verify(source,never()).manifestSummary();
    }

}

package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssignmentCutoverVerificationServiceTest {
    private final AssignmentProjectionRepository repo=mock(AssignmentProjectionRepository.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentCutoverVerificationService service=
        new AssignmentCutoverVerificationService(repo,core);
    private final AssignmentProjectionRepository.AssignmentSnapshot row=
        new AssignmentProjectionRepository.AssignmentSnapshot(
            "STAFF","shift-01","cs-001","Staff","CS",null,null,null,
            "center-01",LocalDate.of(2026,11,10),
            LocalTime.of(9,0),LocalTime.of(11,0),"FRONT_DESK",
            "ASSIGNED","cm-001",8,false);

    private SchedulingCoreClient.AssignmentManifestSummary summary(
            long rows,long versionSum,long pending,long live,long tracked) {
        return new SchedulingCoreClient.AssignmentManifestSummary(
            rows,versionSum,pending,live,tracked);
    }

    @Test void matchingCommittedManifestAllowsReadinessWithoutCrossDbConnection() {
        when(core.assignmentManifestSummary()).thenReturn(summary(1,8,0,1,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(true);
        when(repo.countAllAssignments()).thenReturn(1L);

        var report=service.verify();
        assertTrue(report.ready());
        assertEquals("READY",report.state());
        assertEquals(1,report.checkedRows());
        verify(core,times(2)).assignmentManifestSummary();
        verify(repo).matchesManifest(row);
    }

    @Test void outdatedOrCorruptJsonNeverPassesCutoverGate() {
        when(core.assignmentManifestSummary()).thenReturn(summary(1,8,0,1,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(false);
        when(repo.countAllAssignments()).thenReturn(1L);

        var report=service.verify();
        assertFalse(report.ready());
        assertEquals("PROJECTION_DRIFT",report.state());
        assertEquals(1,report.mismatches());
        assertEquals(List.of("STAFF:shift-01"),report.sampleMismatchKeys());
    }

    @Test void missingOutboxTriggerIsDetectedEvenIfBothDatabasesAgreeOnCount() {
        // A source schedule was written without a Core outbox row.
        when(core.assignmentManifestSummary()).thenReturn(summary(1,8,0,2,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(true);
        when(repo.countAllAssignments()).thenReturn(1L);

        var report=service.verify();
        assertFalse(report.ready());
        assertEquals("CORE_OUTBOX_INCOMPLETE",report.state());
    }

    @Test void pendingEventsBlockCutoverEvenWhenLocalDataAlreadyMatches() {
        when(core.assignmentManifestSummary()).thenReturn(summary(1,8,1,1,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(true);
        when(repo.countAllAssignments()).thenReturn(1L);

        var report=service.verify();
        assertFalse(report.ready());
        assertEquals("PENDING_PROJECTION_CHANGES",report.state());
    }

    @Test void sourceWriteWhileScanningReturnsInconclusiveNotReady() {
        when(core.assignmentManifestSummary())
            .thenReturn(summary(1,8,0,1,1),summary(1,9,0,1,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(true);
        when(repo.countAllAssignments()).thenReturn(1L);

        var report=service.verify();
        assertFalse(report.ready());
        assertEquals("SOURCE_CHANGED_DURING_SCAN",report.state());
    }

    @Test void repeatedOrUnorderedManifestCursorFailsClosed() {
        when(core.assignmentManifestSummary()).thenReturn(summary(2,16,0,2,2));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row,row));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,service::verify).getStatusCode());
        verify(repo,never()).countAllAssignments();
    }

    @Test void localGhostTombstoneCountPreventsCutover() {
        when(core.assignmentManifestSummary()).thenReturn(summary(1,8,0,1,1));
        when(core.assignmentManifest("","",250)).thenReturn(List.of(row));
        when(repo.matchesManifest(row)).thenReturn(true);
        when(repo.countAllAssignments()).thenReturn(2L);
        var result=service.verify();
        assertFalse(result.ready());
        assertEquals("PROJECTION_DRIFT",result.state());
    }
}

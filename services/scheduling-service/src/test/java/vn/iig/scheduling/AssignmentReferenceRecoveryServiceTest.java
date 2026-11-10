package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AssignmentReferenceRecoveryServiceTest {
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AssignmentDomainOutboxRepository outbox=mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=mock(AssignmentCommandLifecycleService.class);
    private final AssignmentReferenceRecoveryService service=
        new AssignmentReferenceRecoveryService(core,outbox,lifecycle);
    private final AssignmentDomainOutboxRepository.Event event=
        new AssignmentDomainOutboxRepository.Event(
            "event-2","cm001","EDIT_STAFF","edit-1","STAFF","shift-1",
            2,"UPSERT","{}","a".repeat(64),10);

    @Test void durableCoreReceiptAllowsExactFailedEventToBeAcknowledged() {
        var proof=new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-2",2,"a".repeat(64));
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1","event-2")).thenReturn(proof);
        when(lifecycle.reconcileVerified(event,proof)).thenReturn(true);
        var result=service.verifyAndRecover("event-2");
        assertEquals("COMMITTED",result.state());
        assertTrue(result.coreReceiptVerified());
        var sequence=inOrder(core,outbox,lifecycle);
        sequence.verify(core).requireCoreWriterQuiesced();
        sequence.verify(outbox).unresolvedEvent("event-2");
        sequence.verify(core).committedAssignmentReceipt("STAFF","shift-1","event-2");
        sequence.verify(lifecycle).reconcileVerified(event,proof);
    }

    private static final String TEACHER_PAYLOAD="""
        {"id":"teaching-1","teacherId":"teacher-1","classId":"class-1",
         "date":"2027-02-11","startTime":"09:00","endTime":"11:00",
         "status":"ASSIGNED","assignedBy":"tc-1"}""";

    private AssignmentDomainOutboxRepository.Event teacher(String payload) {
        return new AssignmentDomainOutboxRepository.Event(
            "event-teacher","tc001","ASSIGN_TEACHER","tc-request-1",
            "TEACHING","teaching-1",1,"UPSERT",payload,"c".repeat(64),10);
    }
    private SchedulingCoreClient.DurableAssignmentReferenceReceipt teacherProof(
        String teacherId) {
        return new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-teacher",1,"c".repeat(64),teacherId,"class-1",
            java.time.LocalDate.of(2027,2,11),java.time.LocalTime.of(9,0),
            java.time.LocalTime.of(11,0),"ASSIGNED","tc-1");
    }

    @Test void confirmedTeacherCanBeRecoveredOnlyWithItsExactImmutableCoreEvent() {
        var teaching=teacher(TEACHER_PAYLOAD);
        var proof=teacherProof("teacher-1");
        when(outbox.unresolvedEvent("event-teacher")).thenReturn(teaching);
        when(core.committedAssignmentReceipt("TEACHING","teaching-1","event-teacher"))
            .thenReturn(proof);
        when(lifecycle.reconcileVerified(teaching,proof)).thenReturn(true);
        var result=service.verifyAndRecover("event-teacher");
        assertEquals("COMMITTED",result.state());
        assertTrue(result.coreReceiptVerified());
        verify(lifecycle).reconcileVerified(teaching,proof);
    }

    @Test void unchangedImmutableReceiptCannotHideChangedCoreTeacher() {
        var teaching=teacher(TEACHER_PAYLOAD);
        when(outbox.unresolvedEvent("event-teacher")).thenReturn(teaching);
        when(core.committedAssignmentReceipt("TEACHING","teaching-1","event-teacher"))
            .thenReturn(teacherProof("teacher-2"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.verifyAndRecover("event-teacher")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void unchangedImmutableReceiptCannotHideChangedTeacherTime() {
        var teaching=teacher(TEACHER_PAYLOAD);
        var evidence=new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-teacher",1,"c".repeat(64),"teacher-1","class-1",
            java.time.LocalDate.of(2027,2,11),java.time.LocalTime.of(10,0),
            java.time.LocalTime.of(12,0),"ASSIGNED","tc-1");
        when(outbox.unresolvedEvent("event-teacher")).thenReturn(teaching);
        when(core.committedAssignmentReceipt("TEACHING","teaching-1","event-teacher"))
            .thenReturn(evidence);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.verifyAndRecover("event-teacher")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void absentTeacherReceiptCannotFinalizeLocalSchedulingWork() {
        var teaching=teacher(TEACHER_PAYLOAD);
        when(outbox.unresolvedEvent("event-teacher")).thenReturn(teaching);
        when(core.committedAssignmentReceipt("TEACHING","teaching-1","event-teacher"))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Core does not confirm teacher reference"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.verifyAndRecover("event-teacher")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void teacherEventAboveRevisionOneIsNotForgedIntoCoreRecovery() {
        var editing=new AssignmentDomainOutboxRepository.Event(
            "event-edit","tc001","ASSIGN_TEACHER","tc-request-1",
            "TEACHING","teaching-1",2,"UPSERT","{}","c".repeat(64),10);
        when(outbox.unresolvedEvent("event-edit")).thenReturn(editing);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.verifyAndRecover("event-edit")).getStatusCode());
        verify(core,never()).committedAssignmentReceipt(
            anyString(),anyString(),anyString());
        verifyNoInteractions(lifecycle);
    }

    @Test void differentCoreDigestNeverAdvancesSchedulingInbox() {
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1","event-2")).thenReturn(
            new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
                "event-2",2,"b".repeat(64)));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.verifyAndRecover("event-2")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void nonexistentCoreProofLeavesEventUnresolved() {
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1","event-2"))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "No matching Core receipt"));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.verifyAndRecover("event-2")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void coreStillWritingBlocksReconciliation() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"Core active"))
            .when(core).requireCoreWriterQuiesced();
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.verifyAndRecover("event-2")).getStatusCode());
        verifyNoInteractions(outbox,lifecycle);
    }

    @Test void unsuccessfulBatchKeepsManualReconciliationState() {
        var proof=new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
            "event-2",2,"a".repeat(64));
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1","event-2")).thenReturn(proof);
        when(lifecycle.reconcileVerified(event,proof)).thenReturn(false);
        assertEquals("COMPENSATING",service.verifyAndRecover("event-2").state());
    }
}

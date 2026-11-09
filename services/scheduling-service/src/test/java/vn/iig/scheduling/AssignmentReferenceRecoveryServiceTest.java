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
        when(core.committedAssignmentReceipt("STAFF","shift-1")).thenReturn(proof);
        when(lifecycle.reconcileVerified(event,proof)).thenReturn(true);
        var result=service.verifyAndRecover("event-2");
        assertEquals("COMMITTED",result.state());
        assertTrue(result.coreReceiptVerified());
        var sequence=inOrder(core,outbox,lifecycle);
        sequence.verify(core).requireCoreWriterQuiesced();
        sequence.verify(outbox).unresolvedEvent("event-2");
        sequence.verify(core).committedAssignmentReceipt("STAFF","shift-1");
        sequence.verify(lifecycle).reconcileVerified(event,proof);
    }

    @Test void differentCoreDigestNeverAdvancesSchedulingInbox() {
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1")).thenReturn(
            new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
                "event-2",2,"b".repeat(64)));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.verifyAndRecover("event-2")).getStatusCode());
        verifyNoInteractions(lifecycle);
    }

    @Test void nonexistentCoreProofLeavesEventUnresolved() {
        when(outbox.unresolvedEvent("event-2")).thenReturn(event);
        when(core.committedAssignmentReceipt("STAFF","shift-1"))
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
        when(core.committedAssignmentReceipt("STAFF","shift-1")).thenReturn(proof);
        when(lifecycle.reconcileVerified(event,proof)).thenReturn(false);
        assertEquals("COMPENSATING",service.verifyAndRecover("event-2").state());
    }
}

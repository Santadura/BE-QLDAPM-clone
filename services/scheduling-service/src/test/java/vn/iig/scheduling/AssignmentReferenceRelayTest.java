package vn.iig.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssignmentReferenceRelayTest {
    private final AssignmentDomainOutboxRepository outbox=
        mock(AssignmentDomainOutboxRepository.class);
    private final AssignmentCommandLifecycleService lifecycle=
        mock(AssignmentCommandLifecycleService.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);

    private final AssignmentDomainOutboxRepository.Event event=
        new AssignmentDomainOutboxRepository.Event(
            "event-01","cm001","ASSIGN_STAFF","cmd-1",
            "STAFF","shift-1",1,"UPSERT","{\"id\":\"shift-1\"}",
            "a".repeat(64),0);

    @Test void disabledFlagDoesNotEvenReadOutbox() {
        new AssignmentReferenceRelay(outbox,lifecycle,core,false).relay();
        verifyNoInteractions(outbox,lifecycle,core);
    }

    @Test void coreCommitMustPrecedeLocalReferenceAck() {
        when(outbox.pending(50)).thenReturn(List.of(event));
        when(core.applyAssignmentReference(event)).thenReturn(
            new SchedulingCoreClient.AssignmentReferenceReceipt(
                "event-01",1,"APPLIED"));

        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();

        var order=inOrder(core,lifecycle);
        order.verify(core).applyAssignmentReference(event);
        order.verify(lifecycle).confirmReference(
            "cm001","ASSIGN_STAFF","cmd-1","event-01","a".repeat(64));
        verify(outbox,never()).retryLater(anyString(),anyString());
    }

    @Test void lostCoreHttpResponseKeepsImmutableEventForReplay() {
        when(outbox.pending(50)).thenReturn(List.of(event));
        when(core.applyAssignmentReference(event))
            .thenThrow(new IllegalStateException("Network connection reset"));

        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();

        verify(lifecycle,never()).confirmReference(
            anyString(),anyString(),anyString(),anyString(),anyString());
        verify(outbox).retryLater(eq("event-01"),contains("reference delivery failed"));
    }

    @Test void supersededEventWithoutExactCoreProofCanNeverBeAcknowledged() {
        when(outbox.pending(50)).thenReturn(List.of(event));
        when(core.applyAssignmentReference(event)).thenReturn(
            new SchedulingCoreClient.AssignmentReferenceReceipt(
                "event-01",1,"SUPERSEDED"));
        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();
        verify(lifecycle,never()).confirmReference(
            anyString(),anyString(),anyString(),anyString(),anyString());
        verify(outbox).retryLater(eq("event-01"),anyString());
    }

    @Test void mismatchedCoreReceiptCannotAckAnotherEvent() {
        when(outbox.pending(50)).thenReturn(List.of(event));
        when(core.applyAssignmentReference(event)).thenReturn(
            new SchedulingCoreClient.AssignmentReferenceReceipt(
                "event-other",1,"APPLIED"));

        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();

        verifyNoInteractions(lifecycle);
        verify(outbox).retryLater(eq("event-01"),anyString());
    }

    @Test void tenthFailedDeliveryStopsAutomaticReplayAndRequiresReconciliation() {
        var exhausted=new AssignmentDomainOutboxRepository.Event(
            "event-01","cm001","ASSIGN_STAFF","cmd-1",
            "STAFF","shift-1",1,"UPSERT","{\"id\":\"shift-1\"}",
            "a".repeat(64),9);
        when(outbox.pending(50)).thenReturn(List.of(exhausted));
        when(core.applyAssignmentReference(exhausted))
            .thenThrow(new IllegalStateException("Core projection unreachable"));
        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();
        var order=inOrder(outbox,lifecycle);
        order.verify(outbox).retryLater(eq("event-01"),anyString());
        order.verify(lifecycle).flagForReconciliation(
            "cm001","ASSIGN_STAFF","cmd-1","event-01");
        verify(lifecycle,never()).confirmReference(
            anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void coreMayHaveCommittedEvenIfLocalAckFailsSoRetrySameEvent() {
        when(outbox.pending(50)).thenReturn(List.of(event));
        when(core.applyAssignmentReference(event)).thenReturn(
            new SchedulingCoreClient.AssignmentReferenceReceipt(
                "event-01",1,"ALREADY_APPLIED"));
        when(lifecycle.confirmReference(
            "cm001","ASSIGN_STAFF","cmd-1","event-01","a".repeat(64)))
            .thenThrow(new IllegalStateException("Scheduling DB unavailable"));

        new AssignmentReferenceRelay(outbox,lifecycle,core,true).relay();

        verify(outbox).retryLater(eq("event-01"),anyString());
    }
}

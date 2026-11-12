package vn.iig.scheduling;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssignmentReceiptRecoveryWorkerTest {
    final AssignmentDomainOutboxRepository outbox=
        mock(AssignmentDomainOutboxRepository.class);
    final AssignmentReferenceRecoveryService recovery=
        mock(AssignmentReferenceRecoveryService.class);
    final AssignmentReceiptRecoveryWorker worker=
        new AssignmentReceiptRecoveryWorker(outbox,recovery);
    final AssignmentDomainOutboxRepository.Event event=
        new AssignmentDomainOutboxRepository.Event("evt-1","tc001",
            "ASSIGN_TEACHER","key-1","TEACHING","schedule-1",1,
            "UPSERT","{}","a".repeat(64),10);

    @Test void committedCoreReceiptRecoversExactEvent() {
        when(recovery.verifyAndRecover("evt-1"))
            .thenReturn(new AssignmentReferenceRecoveryService.Recovery(
                "evt-1","COMMITTED",true));
        assertTrue(worker.recover(event));
        verify(recovery).verifyAndRecover("evt-1");
    }

    @Test void batchWithoutAllCoreReceiptsRemainsCompensating() {
        when(recovery.verifyAndRecover("evt-1"))
            .thenReturn(new AssignmentReferenceRecoveryService.Recovery(
                "evt-1","COMPENSATING",true));
        assertFalse(worker.recover(event));
    }

    @Test void missingOrDivergentCoreReceiptCannotClearUnknownReference() {
        when(recovery.verifyAndRecover("evt-1"))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "Core receipt absent or mismatched"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->worker.recover(event)).getStatusCode());
        reset(recovery);
        when(recovery.verifyAndRecover("evt-1"))
            .thenReturn(new AssignmentReferenceRecoveryService.Recovery(
                "wrong-event","COMMITTED",true));
        assertThrows(IllegalStateException.class,()->worker.recover(event));
    }

    @Test void unknownEventDoesNotTriggerNetworkCalls() {
        assertThrows(IllegalArgumentException.class,()->worker.recover(null));
        verifyNoInteractions(recovery);
    }

    @Test void oneFailedReceiptDoesNotBlockRecoveryOfNext() {
        var second=new AssignmentDomainOutboxRepository.Event("evt-2","tc001",
            "ASSIGN_TEACHER","key-2","TEACHING","schedule-2",1,
            "UPSERT","{}","b".repeat(64),10);
        when(outbox.awaitingVerifiedRecovery(25)).thenReturn(List.of(event,second));
        when(recovery.verifyAndRecover("evt-1")).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        when(recovery.verifyAndRecover("evt-2")).thenReturn(
            new AssignmentReferenceRecoveryService.Recovery("evt-2","COMMITTED",true));
        worker.tick();
        verify(recovery).verifyAndRecover("evt-2");
    }

    @Test void optInOnlyAndNoNetworkWritesInWorker() {
        var flag=AssignmentReceiptRecoveryWorker.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(flag);
        assertArrayEquals(new String[]{"iig.scheduling.receipt-recovery-enabled"},flag.name());
        assertEquals("true",flag.havingValue());
        assertFalse(flag.matchIfMissing());
    }
}

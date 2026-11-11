package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Source/outbox handback survives an ambiguous Core response and JVM restart. */
class AcademicCandidateHandoffRecoveryTest {
    private final AcademicSessionCandidateRepository repo=
        mock(AcademicSessionCandidateRepository.class);
    private final AcademicCandidateClaimClient core=
        mock(AcademicCandidateClaimClient.class);
    private final AcademicCandidateHandoffRecovery relay=
        new AcademicCandidateHandoffRecovery(repo,core);

    private AcademicSessionCandidateRepository.Evidence proof(String requestKey) {
        return new AcademicSessionCandidateRepository.Evidence(
            "admin",requestKey,"a".repeat(64),"class-1","session-1",
            5L,2L,3L,4L,5L,
            "00000000-0000-4000-8000-000000000111","c".repeat(64));
    }

    @Test void releaseIsConfirmedBeforeLocalImmutableReceipt() {
        var event=proof("one");
        when(repo.committedEvidence(event.actor(),event.key(),event.commandId()))
            .thenReturn(event);
        relay.recover(event);
        var order=inOrder(repo,core);
        order.verify(repo).committedEvidence(event.actor(),event.key(),event.commandId());
        order.verify(core).release(new AcademicCandidateClaimClient.Release(
            new AcademicCandidateClaimClient.Claim(
                event.actor(),event.key(),event.digest(),event.classId(),
                event.sessionId(),event.eventId(),event.revision(),
                event.academicGeneration(),event.attendanceGeneration(),
                event.schedulingGeneration()),event.commandId(),event.outboxHash()));
        order.verify(repo).recordHandoff(event);
    }

    @Test void remoteTimeoutMustNeverFabricateLocalRelease() {
        var event=proof("one");
        when(repo.committedEvidence(event.actor(),event.key(),event.commandId()))
            .thenReturn(event);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(core).release(any());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->relay.recover(event)).getStatusCode());
        verify(repo,never()).recordHandoff(any());
    }

    @Test void modifiedIndependentEvidenceCannotReleaseCore() {
        var event=proof("one");
        when(repo.committedEvidence(event.actor(),event.key(),event.commandId()))
            .thenReturn(proof("another"));
        assertThrows(IllegalStateException.class,()->relay.recover(event));
        verifyNoInteractions(core);
        verify(repo,never()).recordHandoff(any());
    }

    @Test void oneFailedCommandDoesNotStarveOtherCommittedCandidate() {
        var bad=proof("bad");
        var good=proof("good");
        when(repo.pendingHandoffs(25)).thenReturn(List.of(bad,good));
        when(repo.committedEvidence(bad.actor(),bad.key(),bad.commandId()))
            .thenReturn(bad);
        when(repo.committedEvidence(good.actor(),good.key(),good.commandId()))
            .thenReturn(good);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(core).release(argThat(r->"bad".equals(r.claim().requestKey())));
        assertDoesNotThrow(relay::reconcile);
        verify(repo,never()).recordHandoff(bad);
        verify(repo).recordHandoff(good);
    }

    @Test void incompleteOrMissingOutboxNeverReleasesCore() {
        var invalid=new AcademicSessionCandidateRepository.Evidence(
            "admin","one","a".repeat(64),"class-1","session-1",5,2,
            3,4,5,"00000000-0000-4000-8000-000000000111",null);
        assertThrows(IllegalArgumentException.class,()->relay.recover(invalid));
        verifyNoInteractions(repo,core);
    }
}

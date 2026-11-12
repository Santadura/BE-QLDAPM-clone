package vn.iig.academic;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateHandoffRecoveryTest {
    private final AcademicRosterCandidateRepository repo=
        mock(AcademicRosterCandidateRepository.class);
    private final AcademicRosterClaimClient core=
        mock(AcademicRosterClaimClient.class);
    private final AcademicRosterCandidateHandoffRecovery relay=
        new AcademicRosterCandidateHandoffRecovery(repo,core);

    private final AcademicRosterCandidateRepository.Evidence saved=
        new AcademicRosterCandidateRepository.Evidence(
            "admin","request-1","a".repeat(64),"TARGET","target-1",
            8,9,2,"UPSERT","b381ee38-d42f-48e7-bbf8-24198e329503","b".repeat(64));

    @Test void crashBeforeAcademicCommitHasNoLocalEvidenceAndNoRelease() {
        when(repo.pendingHandoffs(25)).thenReturn(List.of());
        relay.reconcile();
        verifyNoInteractions(core);
        verify(repo,never()).recordHandoff(any());
    }
    @Test void committedAcademicEvidenceReleasesCoreWithExactImmutableReceipt() {
        when(repo.committedEvidence(saved.actor(),saved.key(),saved.commandId()))
            .thenReturn(saved);
        relay.recover(saved);
        verify(core).release(argThat(r->
            saved.commandId().equals(r.candidateCommandId()) &&
            saved.outboxHash().equals(r.outboxHash()) &&
            saved.digest().equals(r.claim().requestHash()) &&
            saved.writerGeneration()==r.claim().writerGeneration()));
        verify(repo).recordHandoff(saved);
    }
    @Test void crashAfterCoreReleaseButBeforeLocalAckReplaysIdempotently() {
        when(repo.committedEvidence(saved.actor(),saved.key(),saved.commandId()))
            .thenReturn(saved);
        doThrow(new IllegalStateException("Academic PostgreSQL connection lost"))
            .doNothing().when(repo).recordHandoff(saved);
        assertThrows(IllegalStateException.class,()->relay.recover(saved));
        relay.recover(saved);
        verify(core,times(2)).release(any());
        verify(repo,times(2)).recordHandoff(saved);
    }
    @Test void modifiedOrMissingLocalOutboxCannotReleaseCore() {
        when(repo.committedEvidence(saved.actor(),saved.key(),saved.commandId()))
            .thenThrow(new IllegalStateException("Missing immutable Academic outbox"));
        assertThrows(IllegalStateException.class,()->relay.recover(saved));
        verifyNoInteractions(core);
        verify(repo,never()).recordHandoff(any());
    }
    @Test void corruptedScanEvidenceCannotReleaseCoreEvenWhenCommandExists() {
        var altered=new AcademicRosterCandidateRepository.Evidence(
            saved.actor(),saved.key(),saved.digest(),saved.entityType(),
            saved.entityId(),saved.eventId(),saved.revision(),
            saved.writerGeneration(),saved.action(),saved.commandId(),
            "c".repeat(64));
        when(repo.committedEvidence(saved.actor(),saved.key(),saved.commandId()))
            .thenReturn(saved);
        assertThrows(IllegalStateException.class,()->relay.recover(altered));
        verifyNoInteractions(core);
    }
}

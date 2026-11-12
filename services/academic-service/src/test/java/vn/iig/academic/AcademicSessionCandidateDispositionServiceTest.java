package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateDispositionServiceTest {
    private static final String CMD="b381ee38-d42f-48e7-bbf8-24198e329503";
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicSessionSourceClient source=mock(AcademicSessionSourceClient.class);
    final AcademicCandidateClaimClient claims=mock(AcademicCandidateClaimClient.class);
    final AcademicCandidateReconciliation reconciliation=mock(AcademicCandidateReconciliation.class);
    final AcademicSessionCandidateDispositionRepository dispositions=
        mock(AcademicSessionCandidateDispositionRepository.class);
    final AcademicSessionCandidateDispositionService service=
        new AcademicSessionCandidateDispositionService(identity,source,
            claims,reconciliation,dispositions);
    final AcademicSessionCandidateDispositionService.Request request=
        new AcademicSessionCandidateDispositionService.Request("key-1",CMD);
    final AcademicSessionSourceClient.WriterFence CORE=
        new AcademicSessionSourceClient.WriterFence("CORE",4,"CORE",5,"CORE",6);
    final AcademicCandidateClaimClient.Summary RELEASED=
        new AcademicCandidateClaimClient.Summary(0,1,2);
    final AcademicCandidateReconciliation.Diagnosis EXACT=
        new AcademicCandidateReconciliation.Diagnosis(
            "CONSISTENT_RELEASED","admin","key-1","RELEASED",CMD,
            true,true,false);

    void allow() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(source.writerFence()).thenReturn(CORE);
        when(claims.summary()).thenReturn(RELEASED);
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(EXACT);
        when(dispositions.summary()).thenReturn(
            new AcademicSessionCandidateDispositionRepository.Summary(1,0,1,0,0),
            new AcademicSessionCandidateDispositionRepository.Summary(1,1,0,0,0));
        when(dispositions.discard("admin","key-1",CMD,"admin"))
            .thenReturn(new AcademicSessionCandidateDispositionRepository.Receipt(
                "key-1",CMD,"REHEARSAL_DISCARDED"));
    }

    @Test void exactCoreOwnedReleasedRehearsalCanBeAuditedWithoutWritingCore()
            throws Exception {
        allow();
        assertEquals("REHEARSAL_DISCARDED",
            service.discard("Bearer admin",request).disposition());
        verify(reconciliation,times(2))
            .inspect("Bearer admin","admin","key-1");
        verify(dispositions).discard("admin","key-1",CMD,"admin");
        assertNotNull(AcademicSessionCandidateDispositionService.class
            .getMethod("discard",String.class,
                AcademicSessionCandidateDispositionService.Request.class)
            .getAnnotation(Transactional.class));
    }

    @Test void replayOfAlreadyDisposedClaimIsIdempotent() {
        allow();
        when(dispositions.summary()).thenReturn(
            new AcademicSessionCandidateDispositionRepository.Summary(1,1,0,0,0));
        assertEquals("REHEARSAL_DISCARDED",
            service.discard("Bearer admin",request).disposition());
    }

    @Test void cannotDiscardBeforeAllThreeCoreWriterControlsAreRestored() {
        allow();
        when(source.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence(
                "CORE",4,"QUIESCED",5,"CORE",6));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(reconciliation,dispositions);
    }

    @Test void openCoreClaimBlocksDisposal() {
        allow();
        when(claims.summary()).thenReturn(
            new AcademicCandidateClaimClient.Summary(1,0,1));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(reconciliation,dispositions);
    }

    @Test void releasedClaimWithWrongOutboxCannotBeDisposed() {
        allow();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(new AcademicCandidateReconciliation.Diagnosis(
                "EVIDENCE_DIVERGED","admin","key-1","RELEASED",CMD,
                false,true,true));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(dispositions,never()).discard(anyString(),anyString(),anyString(),anyString());
    }

    @Test void losingWriterGenerationAfterLocalInsertRollsBack() {
        allow();
        when(source.writerFence()).thenReturn(CORE,
            new AcademicSessionSourceClient.WriterFence("CORE",8,"CORE",5,"CORE",6));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(dispositions).discard("admin","key-1",CMD,"admin");
    }

    @Test void missingPostCommitCoreProofRollsBackDisposition() {
        allow();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(EXACT,
                new AcademicCandidateReconciliation.Diagnosis(
                    "LOCAL_ACK_PENDING","admin","key-1","RELEASED",CMD,
                    true,false,true));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(dispositions).discard("admin","key-1",CMD,"admin");
    }

    @Test void unauthorizedPrincipalCannotQueryClaimOrDispositionLedger() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer teacher",request)).getStatusCode());
        verifyNoInteractions(source,claims,reconciliation,dispositions);
    }

    @Test void invalidCommandRejectedBeforeExternalCalls() {
        var invalid=new AcademicSessionCandidateDispositionService.Request("key-1","bad");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",invalid)).getStatusCode());
        verifyNoInteractions(identity,source,claims,reconciliation,dispositions);
    }
}

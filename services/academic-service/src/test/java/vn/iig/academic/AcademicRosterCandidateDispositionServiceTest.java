package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateDispositionServiceTest {
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicRosterSourceClient source=mock(AcademicRosterSourceClient.class);
    final AcademicRosterClaimClient claims=mock(AcademicRosterClaimClient.class);
    final AcademicRosterCandidateDispositionRepository ledger=
        mock(AcademicRosterCandidateDispositionRepository.class);
    final AcademicRosterCandidateReconciliation reconciliation=
        mock(AcademicRosterCandidateReconciliation.class);
    final AcademicRosterCandidateDispositionService service=
        new AcademicRosterCandidateDispositionService(identity,source,claims,
            ledger,reconciliation);
    final AcademicRosterCandidateDispositionService.Request request=
        new AcademicRosterCandidateDispositionService.Request(
            "key-1","b381ee38-d42f-48e7-bbf8-24198e329503");
    final AcademicRosterSourceClient.WriterFence owned=
        new AcademicRosterSourceClient.WriterFence("CORE",3);
    final AcademicRosterClaimClient.Summary released=
        new AcademicRosterClaimClient.Summary(0,2,4);

    AcademicRosterCandidateReconciliation.Diagnosis diagnosis(String state,
            String commandId,boolean handoff,boolean disposed) {
        return new AcademicRosterCandidateReconciliation.Diagnosis(
            state,"admin","key-1","RELEASED",commandId,true,
            handoff,disposed,
            !"REHEARSAL_DISCARDED".equals(state));
    }

    void adminAndCoreOwned() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(source.writerFence()).thenReturn(owned);
        when(claims.summary()).thenReturn(released);
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(
                diagnosis("RELEASED_REHEARSAL_UNAPPLIED",
                    request.commandId(),true,false),
                diagnosis("REHEARSAL_DISCARDED",
                    request.commandId(),true,true));
    }

    @Test void exactAdminDiscardRequiresStableCoreOwnership() {
        adminAndCoreOwned();
        var answer=service.discard("Bearer admin",request);
        assertEquals("REHEARSAL_DISCARDED",answer.disposition());
        assertEquals(request.commandId(),answer.commandId());
        var order=inOrder(identity,source,claims,ledger,reconciliation);
        order.verify(identity).currentPrincipal("Bearer admin");
        order.verify(source).writerFence();
        order.verify(claims).summary();
        order.verify(reconciliation).inspect("Bearer admin","admin","key-1");
        order.verify(ledger).discard("admin","key-1",request.commandId(),"admin");
        order.verify(reconciliation).inspect("Bearer admin","admin","key-1");
        order.verify(source).writerFence();
        order.verify(claims).summary();
    }
    @Test void frozenCoreCannotPretendToDiscardUnappliedWrite() {
        adminAndCoreOwned();
        when(source.writerFence()).thenReturn(
            new AcademicRosterSourceClient.WriterFence("QUIESCED",4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(ledger);
    }
    @Test void anOpenCoreClaimPreventsRehearsalDisposal() {
        adminAndCoreOwned();
        when(claims.summary()).thenReturn(
            new AcademicRosterClaimClient.Summary(1,1,3));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(ledger);
    }
    @Test void teacherCannotAccessRehearsalDispositionOrEvidence() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer teacher",request)).getStatusCode());
        verifyNoInteractions(ledger,claims,source);
    }
    @Test void coreGenerationChangingAfterLocalWriteThrowsAndTransactionRollsBack() throws Exception {
        adminAndCoreOwned();
        when(source.writerFence()).thenReturn(owned,
            new AcademicRosterSourceClient.WriterFence("CORE",5));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(ledger).discard("admin","key-1",request.commandId(),"admin");
        assertNotNull(AcademicRosterCandidateDispositionService.class
            .getMethod("discard",String.class,
                AcademicRosterCandidateDispositionService.Request.class)
            .getAnnotation(Transactional.class));
    }
    @Test void coreClaimLedgerAbaAlsoTriggersTransactionalRollback() {
        adminAndCoreOwned();
        when(claims.summary()).thenReturn(released,
            new AcademicRosterClaimClient.Summary(0,3,6));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
    }
    @Test void coreReleaseMissingOrMismatchedCannotBeHiddenByLocalReceipt() {
        adminAndCoreOwned();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(new AcademicRosterCandidateReconciliation.Diagnosis(
                "EVIDENCE_DIVERGED","admin","key-1","RELEASED",
                request.commandId(),false,true,false,true));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(ledger,never()).discard(anyString(),anyString(),anyString(),anyString());
    }

    @Test void coreOnlyOrphanCannotBeDisposedOnLocalHandoffAssumptions() {
        adminAndCoreOwned();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(new AcademicRosterCandidateReconciliation.Diagnosis(
                "CORE_ONLY_ORPHAN","admin","key-1","OPEN",
                null,false,false,false,true));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(ledger);
    }

    @Test void validProofOfAnotherCommandCannotDisposeThisCommand() {
        adminAndCoreOwned();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(diagnosis("RELEASED_REHEARSAL_UNAPPLIED",
                "0fe9140e-8a26-4096-857e-58fb3119a04a",true,false));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verifyNoInteractions(ledger);
    }

    @Test void missingPostDiscardProofMustRollbackTransaction() {
        adminAndCoreOwned();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(
                diagnosis("RELEASED_REHEARSAL_UNAPPLIED",
                    request.commandId(),true,false),
                diagnosis("RELEASED_REHEARSAL_UNAPPLIED",
                    request.commandId(),true,false));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.discard("Bearer admin",request)).getStatusCode());
        verify(ledger).discard("admin","key-1",request.commandId(),"admin");
    }

    @Test void alreadyDisposedExactRehearsalCanBeRetriedIdempotently() {
        adminAndCoreOwned();
        when(reconciliation.inspect("Bearer admin","admin","key-1"))
            .thenReturn(diagnosis("REHEARSAL_DISCARDED",
                request.commandId(),true,true));
        assertEquals("REHEARSAL_DISCARDED",
            service.discard("Bearer admin",request).disposition());
        verify(ledger).discard("admin","key-1",request.commandId(),"admin");
    }

}

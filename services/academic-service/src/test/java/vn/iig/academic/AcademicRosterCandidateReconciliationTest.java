package vn.iig.academic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateReconciliationTest {
    private final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    private final AcademicRosterClaimClient core=mock(AcademicRosterClaimClient.class);
    private final AcademicRosterCandidateRepository local=
        mock(AcademicRosterCandidateRepository.class);
    private final AcademicRosterCandidateReconciliation svc=
        new AcademicRosterCandidateReconciliation(identity,core,local);
    private final String actor="admin",key="request-1";
    private final String id="b381ee38-d42f-48e7-bbf8-24198e329503";
    private final AcademicRosterClaimClient.Claim claim=
        new AcademicRosterClaimClient.Claim(actor,key,"a".repeat(64),
            "TARGET","target-1",8,9,2);
    private final AcademicRosterCandidateRepository.Evidence proof=
        new AcademicRosterCandidateRepository.Evidence(actor,key,"a".repeat(64),
            "TARGET","target-1",8,9,2,"UPSERT",id,"b".repeat(64));

    @BeforeEach void liveAdmin() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
    }
    private void coreState(String state) {
        when(core.inspect(actor,key)).thenReturn(
            new AcademicRosterClaimClient.ClaimInspection(claim,state,
                "RELEASED".equals(state)?id:null,
                "RELEASED".equals(state)?proof.outboxHash():null));
    }
    private void localState(boolean handoff,boolean disposed) {
        when(local.inspectCandidate(actor,key)).thenReturn(
            new AcademicRosterCandidateRepository.LocalInspection(id,
                true,handoff,disposed,handoff,disposed,proof));
    }

    @Test void coreOnlyOrphanCannotBeAutoReleased() {
        coreState("OPEN");
        var diagnosis=svc.inspect("Bearer admin",actor,key);
        assertEquals("CORE_ONLY_ORPHAN",diagnosis.state());
        assertTrue(diagnosis.requiresManualReview());
        verify(local).inspectCandidate(actor,key);
    }
    @Test void committedOutboxWithoutCoreReleaseIsRetryable() {
        coreState("OPEN");localState(false,false);
        var diagnosis=svc.inspect("Bearer admin",actor,key);
        assertEquals("PENDING_CORE_HANDOFF",diagnosis.state());
        assertTrue(diagnosis.hasCompleteAcademicEvidence());
        assertFalse(diagnosis.requiresManualReview());
    }
    @Test void releasedCoreClaimWithoutLocalReceiptNeedsAckRetry() {
        coreState("RELEASED");localState(false,false);
        assertEquals("LOCAL_ACK_PENDING",svc.inspect("Bearer admin",actor,key).state());
    }
    @Test void releasedRehearsalCannotMasqueradeAsAuthoritativeWrite() {
        coreState("RELEASED");localState(true,false);
        var result=svc.inspect("Bearer admin",actor,key);
        assertEquals("RELEASED_REHEARSAL_UNAPPLIED",result.state());
        assertTrue(result.requiresManualReview());
        assertFalse(result.hasDisposition());
    }
    @Test void explicitlyDiscardedRehearsalHasVerifiedBothLedgers() {
        coreState("RELEASED");localState(true,true);
        var result=svc.inspect("Bearer admin",actor,key);
        assertEquals("REHEARSAL_DISCARDED",result.state());
        assertTrue(result.hasDisposition());
        assertFalse(result.requiresManualReview());
    }
    @Test void mismatchedCoreHashRequiresHumanInvestigation() {
        coreState("RELEASED");localState(true,false);
        when(core.inspect(actor,key)).thenReturn(
            new AcademicRosterClaimClient.ClaimInspection(claim,
                "RELEASED",id,"c".repeat(64)));
        assertEquals("EVIDENCE_DIVERGED",
            svc.inspect("Bearer admin",actor,key).state());
    }
    @Test void brokenLocalOutboxDoesNotBecomeSuccessfulProof() {
        coreState("OPEN");
        when(local.inspectCandidate(actor,key)).thenReturn(
            new AcademicRosterCandidateRepository.LocalInspection(id,
                true,false,false,false,false,null));
        var result=svc.inspect("Bearer admin",actor,key);
        assertEquals("EVIDENCE_DIVERGED",result.state());
        assertTrue(result.requiresManualReview());
    }
    @Test void forgedLocalDispositionWithCoreStillOpenIsManual() {
        coreState("OPEN");localState(true,true);
        assertEquals("CORE_OPEN_WITH_LOCAL_RELEASE_RECEIPT",
            svc.inspect("Bearer admin",actor,key).state());
    }
    @Test void teacherIsDeniedBeforeCoreAndAcademicEvidenceQueries() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->svc.inspect("Bearer teacher",actor,key)).getStatusCode());
        verifyNoInteractions(core,local);
    }
    @Test void invalidKeyCannotReachEitherClaimLedger() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.inspect("Bearer admin","admin","invalid key"))
            .getStatusCode());
        verifyNoInteractions(core,local);
    }
}

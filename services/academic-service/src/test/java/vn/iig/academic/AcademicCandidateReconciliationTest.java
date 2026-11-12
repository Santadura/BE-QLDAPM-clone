package vn.iig.academic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCandidateReconciliationTest {
    private final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    private final AcademicCandidateClaimClient core=mock(AcademicCandidateClaimClient.class);
    private final AcademicSessionCandidateRepository local=
        mock(AcademicSessionCandidateRepository.class);
    private final AcademicCandidateReconciliation service=
        new AcademicCandidateReconciliation(identity,core,local);

    private AcademicCandidateClaimClient.Claim claim() {
        return new AcademicCandidateClaimClient.Claim("actor","key",
            "a".repeat(64),"class-1","session-1",5,2,3,4,5);
    }
    private AcademicSessionCandidateRepository.Evidence evidence() {
        return new AcademicSessionCandidateRepository.Evidence(
            "actor","key","a".repeat(64),"class-1","session-1",5,2,
            3,4,5,"00000000-0000-4000-8000-000000000001","b".repeat(64));
    }
    private void allow(String state) {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(core.inspect("actor","key")).thenReturn(
            new AcademicCandidateClaimClient.ClaimInspection(claim(),state,
                "RELEASED".equals(state)?evidence().commandId():null,
                "RELEASED".equals(state)?evidence().outboxHash():null));
    }
    private void local(boolean slot,boolean outbox,boolean ack,boolean ackExact) {
        when(this.local.inspectCandidate("actor","key")).thenReturn(
            new AcademicSessionCandidateRepository.LocalCandidate(
                evidence(),slot,outbox,ack,ackExact));
    }

    @Test void liveAdminIsRequiredEvenForPreviouslyValidToken() {
        when(identity.currentPrincipal("Bearer tc")).thenReturn(
            new AcademicCoreClient.Principal("tc001","ROLE_TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.inspect("Bearer tc","actor","key")).getStatusCode());
        verifyNoInteractions(core,local);
    }
    @Test void badIdentityCannotReachEitherDatabase() {
        allow("OPEN");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.inspect("Bearer admin","actor","bad key"))
            .getStatusCode());
        verifyNoInteractions(core,local);
    }
    @Test void coreOnlyClaimRemainsOrphanWithNoLocalRows() {
        allow("OPEN");
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("CORE_ONLY_ORPHAN",result.state());
        assertTrue(result.requiresManualReview());
        assertFalse(result.hasAtomicAcademicEvidence());
        verify(local).inspectCandidate("actor","key");
        verify(core,never()).release(any());
    }
    @Test void releasedCoreWithoutLocalEvidenceIsNotAHealthyHandoff() {
        allow("RELEASED");
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("RELEASED_WITHOUT_ACADEMIC_EVIDENCE",result.state());
        assertTrue(result.requiresManualReview());
    }
    @Test void exactCommittedCandidateWithOpenCoreIsPendingNotSuccess() {
        allow("OPEN");
        local(true,true,false,false);
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("PENDING_CORE_HANDOFF",result.state());
        assertEquals("OPEN",result.coreState());
        assertTrue(result.hasAtomicAcademicEvidence());
        assertFalse(result.hasLocalHandoffReceipt());
    }
    @Test void brokenOutboxIsNeverConsideredValid() {
        allow("OPEN");
        local(true,false,false,false);
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("EVIDENCE_DIVERGED",result.state());
        assertTrue(result.requiresManualReview());
    }
    @Test void releasedCoreMissingLocalAckCanBeDiagnosedWithoutWriting() {
        allow("RELEASED");
        local(true,true,false,false);
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("LOCAL_ACK_PENDING",result.state());
        assertFalse(result.hasLocalHandoffReceipt());
        verify(local,never()).recordHandoff(any());
    }
    @Test void exactReleasedCoreAndAcademicReceiptAreConsistent() {
        allow("RELEASED");
        local(true,true,true,true);
        assertEquals("CONSISTENT_RELEASED",
            service.inspect("Bearer admin","actor","key").state());
        verify(core,never()).release(any());
    }
    @Test void forgedLocalReleaseWhileCoreOpenNeedsManualReview() {
        allow("OPEN");
        local(true,true,true,true);
        var result=service.inspect("Bearer admin","actor","key");
        assertEquals("CORE_OPEN_WITH_LOCAL_RELEASE_RECEIPT",result.state());
        assertTrue(result.requiresManualReview());
    }
    @Test void mismatchedCoreReleaseDigestIsNotAcknowledged() {
        allow("RELEASED");
        when(core.inspect("actor","key")).thenReturn(
            new AcademicCandidateClaimClient.ClaimInspection(claim(),
                "RELEASED",evidence().commandId(),"c".repeat(64)));
        local(true,true,true,true);
        assertEquals("EVIDENCE_DIVERGED",
            service.inspect("Bearer admin","actor","key").state());
    }
}

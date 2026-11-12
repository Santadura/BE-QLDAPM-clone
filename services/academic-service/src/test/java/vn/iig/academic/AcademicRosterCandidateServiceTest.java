package vn.iig.academic;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterCandidateServiceTest {
    final AcademicCoreClient identity=mock(AcademicCoreClient.class);
    final AcademicRosterCutoverReadiness readiness=mock(AcademicRosterCutoverReadiness.class);
    final AcademicRosterSourceClient source=mock(AcademicRosterSourceClient.class);
    final AcademicRosterCandidateRepository journal=mock(AcademicRosterCandidateRepository.class);
    final AcademicRosterClaimClient claims=mock(AcademicRosterClaimClient.class);
    final AcademicRosterCandidateService svc=
        new AcademicRosterCandidateService(identity,readiness,source,journal,claims);
    final AcademicRosterCutoverReadiness.Evidence proof=
        new AcademicRosterCutoverReadiness.Evidence(4,11,15,4,3,7);
    final AcademicRosterSourceClient.WriterFence frozen=
        new AcademicRosterSourceClient.WriterFence("QUIESCED",4);

    AcademicRosterCandidateService.Request request(String type,String action,
            BigDecimal value) {
        return new AcademicRosterCandidateService.Request("same-key",type,
            "record-1",10,5,action,value);
    }
    void admin() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(readiness.verify()).thenReturn(proof);
        when(source.writerFence()).thenReturn(frozen);
        when(journal.stage(any())).thenReturn(
            new AcademicRosterCandidateRepository.Receipt(
                "command","same-key","TARGET","record-1",10,"STAGED"));
    }
    @Test void targetCandidateIsIndependentAndOnlyAfterFullFrozenProof() {
        admin();
        var request=request("TARGET","UPSERT",new BigDecimal("7.50"));
        assertEquals("STAGED",svc.stage("Bearer admin",request).state());
        var ordered=inOrder(identity,readiness,claims,journal,source);
        ordered.verify(identity).currentPrincipal("Bearer admin");
        ordered.verify(readiness).verify();
        ordered.verify(claims).acquire(argThat(claim->
            "TARGET".equals(claim.entityType()) &&
            claim.sourceEventId()==10 && claim.writerGeneration()==4));
        ordered.verify(journal).stage(argThat(plan->
            "TARGET".equals(plan.entityType()) &&
            plan.proposedValue().compareTo(new BigDecimal("7.50"))==0 &&
            plan.writerGeneration()==4));
        ordered.verify(source).writerFence();
    }
    @Test void resultCandidateSupportsTwoDecimalScore() {
        admin();
        var r=request("RESULT","UPSERT",new BigDecimal("9.25"));
        svc.stage("Bearer admin",r);
        verify(journal).stage(argThat(plan->
            "RESULT".equals(plan.entityType()) &&
            "UPSERT".equals(plan.action()) &&
            plan.proposedValue().compareTo(new BigDecimal("9.25"))==0));
    }
    @Test void deleteCandidateCannotCarryAnyReplacementValue() {
        admin();
        svc.stage("Bearer admin",request("RESULT","DELETE",null));
        verify(journal).stage(argThat(plan->
            "DELETE".equals(plan.action()) && plan.proposedValue()==null));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("TARGET","DELETE",BigDecimal.ONE)))
            .getStatusCode());
    }
    @Test void rejectScoreOverflowAndNegativeTargetsBeforeAnySQL() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("RESULT","UPSERT",new BigDecimal("1000.00"))))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("TARGET","UPSERT",new BigDecimal("-1.00"))))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("RESULT","UPSERT",new BigDecimal("8.123"))))
            .getStatusCode());
        verifyNoInteractions(identity,readiness,journal,source,claims);
    }
    @Test void teacherDeniedBeforeCrossDatabaseRosterHistoryAccess() {
        when(identity.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer teacher",request("RESULT","UPSERT",BigDecimal.ONE)))
            .getStatusCode());
        verifyNoInteractions(readiness,journal,source,claims);
    }
    @Test void noFrozenProofCannotJournalIndependentCandidates() {
        admin();
        when(readiness.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,
                "Core source writer still active"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("TARGET","UPSERT",BigDecimal.ONE)))
            .getStatusCode());
        verifyNoInteractions(journal,source,claims);
    }
    @Test void generationChangedAfterLocalStagingFailsAndRollsBackTransaction() {
        admin();
        when(source.writerFence()).thenReturn(
            new AcademicRosterSourceClient.WriterFence("QUIESCED",6));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("RESULT","UPSERT",BigDecimal.ONE)))
            .getStatusCode());
        verify(journal).stage(any());
        verify(claims).acquire(any());
        verify(claims,never()).release(any());
    }
    @Test void sourceOutageAfterLocalStagingNeverReportsSuccess() {
        admin();
        when(source.writerFence()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Core offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->svc.stage("Bearer admin",request("TARGET","UPSERT",BigDecimal.ONE)))
            .getStatusCode());
    }

    @Test void releaseOnlyAfterMatchingDurableLocalOutboxEvidence() {
        admin();
        var req=request("TARGET","UPSERT",new BigDecimal("7.5"));
        String canonical="academic-roster-candidate-v1\nTARGET\nrecord-1\n10\n5\nUPSERT\n7.50";
        var saved=new AcademicRosterCandidateRepository.Evidence(
            "admin","same-key",AcademicSessionSourceRepository.sha256(canonical),
            "TARGET","record-1",10,5,4,"UPSERT","command","b".repeat(64));
        var receipt=new AcademicRosterCandidateRepository.Receipt(
            "command","same-key","TARGET","record-1",10,"STAGED");
        when(journal.committedEvidence("admin","same-key","command")).thenReturn(saved);
        svc.acknowledge("Bearer admin",req,receipt);
        verify(claims).release(argThat(r->
            saved.commandId().equals(r.candidateCommandId()) &&
            saved.outboxHash().equals(r.outboxHash()) &&
            saved.digest().equals(r.claim().requestHash())));
        verify(journal).recordHandoff(saved);
    }
    @Test void coreReleaseOutageCannotForgeLocalHandoffAcknowledgement() {
        admin();
        var req=request("TARGET","UPSERT",new BigDecimal("7.50"));
        String canonical="academic-roster-candidate-v1\nTARGET\nrecord-1\n10\n5\nUPSERT\n7.50";
        var saved=new AcademicRosterCandidateRepository.Evidence(
            "admin","same-key",AcademicSessionSourceRepository.sha256(canonical),
            "TARGET","record-1",10,5,4,"UPSERT","command","b".repeat(64));
        var receipt=new AcademicRosterCandidateRepository.Receipt(
            "command","same-key","TARGET","record-1",10,"STAGED");
        when(journal.committedEvidence("admin","same-key","command")).thenReturn(saved);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Core claim release unavailable")).when(claims).release(any());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->svc.acknowledge("Bearer admin",req,receipt)).getStatusCode());
        verify(journal,never()).recordHandoff(any());
    }
    @Test void mismatchedRetryCannotReleaseAnotherCommittedProposal() {
        admin();
        var req=request("RESULT","UPSERT",new BigDecimal("9.00"));
        var saved=new AcademicRosterCandidateRepository.Evidence(
            "admin","same-key","a".repeat(64),
            "RESULT","record-1",10,5,4,"UPSERT","command","b".repeat(64));
        var receipt=new AcademicRosterCandidateRepository.Receipt(
            "command","same-key","RESULT","record-1",10,"STAGED");
        when(journal.committedEvidence("admin","same-key","command")).thenReturn(saved);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acknowledge("Bearer admin",req,receipt)).getStatusCode());
        verify(claims,never()).release(any());
    }
}

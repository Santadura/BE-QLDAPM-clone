package vn.iig.academic;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicSessionCandidateServiceTest {
    AcademicCoreClient identity=mock(AcademicCoreClient.class);
    AcademicSessionCutoverReadiness readiness=mock(AcademicSessionCutoverReadiness.class);
    AcademicSessionSourceClient core=mock(AcademicSessionSourceClient.class);
    AcademicSessionCandidateRepository repo=mock(AcademicSessionCandidateRepository.class);
    AcademicCandidateClaimClient claims=mock(AcademicCandidateClaimClient.class);
    AcademicSessionCandidateService service=
        new AcademicSessionCandidateService(identity,readiness,core,repo,claims);
    AcademicSessionCandidateService.Request request() {
        return new AcademicSessionCandidateService.Request(
            "dryrun-key","class-1","lesson-1",5,3,"RESCHEDULE",
            "2027-02-12","09:00","11:00","PLANNED");
    }
    void allow() {
        when(identity.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(readiness.verify()).thenReturn(
            new AcademicSessionCutoverReadiness.Evidence(5,5,2,2,4,5,6));
        when(core.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence(
                "QUIESCED",4,"QUIESCED",5,"QUIESCED",6));
    }
    @Test void onlyAdminAndFrozenHistoryMayStage() {
        allow();
        service.stage("Bearer admin",request());
        verify(repo).stage(argThat(p->p.classId().equals("class-1") &&
            p.sourceEventId()==5 && p.sourceRevision()==3 &&
            p.academicGeneration()==4 && p.attendanceGeneration()==5 &&
            p.schedulingGeneration()==6));
        verify(identity).sessionRevision("class-1","Bearer admin");
        verify(readiness).verify();
        var order=inOrder(claims,repo);
        order.verify(claims).acquire(any());
        order.verify(repo).stage(any());
        verify(claims,never()).release(any());
    }
    @Test void wrongRoleNeverCallsReadinessOrWrites() {
        when(identity.currentPrincipal("Bearer tc")).thenReturn(
            new AcademicCoreClient.Principal("tc001","ROLE_TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer tc",request())).getStatusCode());
        verifyNoInteractions(readiness,repo,claims);
    }
    @Test void incompleteHistoryNeverStages() {
        allow();
        when(readiness.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,"History incomplete"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",request())).getStatusCode());
        verifyNoInteractions(repo,claims);
    }
    @Test void staleGenerationRejectsTransaction() {
        allow();
        when(core.writerFence()).thenReturn(
            new AcademicSessionSourceClient.WriterFence(
                "QUIESCED",7,"QUIESCED",5,"QUIESCED",6));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",request())).getStatusCode());
        verify(repo).stage(any());
        verify(claims).acquire(any());
    }
    @Test void malformedTimesFailBeforeCoreOrLocalSql() {
        var bad=new AcademicSessionCandidateService.Request(
            "key","class-1","lesson-1",5,3,"RESCHEDULE",
            "2027-02-12","13:00","09:00","PLANNED");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",bad)).getStatusCode());
        verifyNoInteractions(identity,readiness,core,repo,claims);
    }
    @Test void rescheduleCannotInventCanceledState() {
        var bad=new AcademicSessionCandidateService.Request(
            "key","class-1","lesson-1",5,3,"RESCHEDULE",
            "2027-02-12","09:00","11:00","CANCELLED");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",bad)).getStatusCode());
        verifyNoInteractions(identity,readiness,core,repo,claims);
    }
    @Test void missingActionIsRejectedWithoutRemoteEffects() {
        var missing=new AcademicSessionCandidateService.Request(
            "key","class-1","lesson-1",5,3,null,
            "2027-02-12","09:00","11:00","PLANNED");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",missing)).getStatusCode());
        verifyNoInteractions(identity,readiness,core,repo,claims);
    }
    private static final String COMMITTED_ID=
        "b381ee38-d42f-48e7-bbf8-24198e329503";

    AcademicSessionCandidateRepository.Receipt stagedReceipt() {
        return new AcademicSessionCandidateRepository.Receipt(
            COMMITTED_ID,"dryrun-key","class-1","lesson-1",5,3,"STAGED");
    }

    AcademicSessionCandidateRepository.Evidence committedProof() {
        var req=request();
        String canonical="academic-candidate-v1\\n"+req.classId()+"\\n"+
            req.sessionId()+"\\n"+req.expectedEventId()+"\\n"+
            req.expectedRevision()+"\\n"+req.action()+"\\n"+
            req.date()+"\\n"+req.startTime()+"\\n"+req.endTime()+"\\n"+
            req.status();
        return new AcademicSessionCandidateRepository.Evidence(
            "admin","dryrun-key",AcademicSessionSourceRepository.sha256(canonical),
            "class-1","lesson-1",5,3,4,5,6,COMMITTED_ID,"b".repeat(64));
    }

    @Test void matchingDurableSessionReceiptCanReleaseOnlyExactClaim() {
        allow();
        var proof=committedProof();
        when(repo.committedEvidence("admin","dryrun-key",COMMITTED_ID))
            .thenReturn(proof);
        service.acknowledge("Bearer admin",request(),stagedReceipt());
        var order=inOrder(repo,claims);
        order.verify(repo).committedEvidence("admin","dryrun-key",COMMITTED_ID);
        order.verify(claims).release(argThat(x->
            COMMITTED_ID.equals(x.candidateCommandId()) &&
            proof.digest().equals(x.claim().requestHash()) &&
            "lesson-1".equals(x.claim().sessionId())));
        order.verify(repo).recordHandoff(proof);
    }

    @Test void changedSessionReceiptIdentityCannotReleaseCoreClaim() {
        var altered=new AcademicSessionCandidateRepository.Receipt(
            COMMITTED_ID,"dryrun-key","class-1","another-lesson",5,3,"STAGED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",request(),altered))
            .getStatusCode());
        var forgedStatus=new AcademicSessionCandidateRepository.Receipt(
            COMMITTED_ID,"dryrun-key","class-1","lesson-1",5,3,"COMMITTED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",request(),forgedStatus))
            .getStatusCode());
        verifyNoInteractions(identity,repo,claims,core);
    }

    @Test void malformedSessionRetryDateNeverBecomesServerErrorOrRelease() {
        var req=request();
        var malformed=new AcademicSessionCandidateService.Request(
            req.requestKey(),req.classId(),req.sessionId(),
            req.expectedEventId(),req.expectedRevision(),req.action(),
            "invalid-date",req.startTime(),req.endTime(),req.status());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",malformed,stagedReceipt()))
            .getStatusCode());
        var inverted=new AcademicSessionCandidateService.Request(
            req.requestKey(),req.classId(),req.sessionId(),
            req.expectedEventId(),req.expectedRevision(),req.action(),
            req.date(),"16:00","12:00",req.status());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",inverted,stagedReceipt()))
            .getStatusCode());
        verifyNoInteractions(identity,repo,claims,core);
    }

    @Test void sessionReceiptCannotSubstituteAnotherRequestKeyOrVersion() {
        var original=request();
        var changed=new AcademicSessionCandidateRepository.Receipt(
            COMMITTED_ID,"different-key","class-1","lesson-1",5,3,"STAGED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",original,changed))
            .getStatusCode());
        var revision=new AcademicSessionCandidateRepository.Receipt(
            COMMITTED_ID,"dryrun-key","class-1","lesson-1",5,4,"STAGED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",original,revision))
            .getStatusCode());
        verifyNoInteractions(identity,repo,claims,core);
    }

    @Test void failedCoreReleaseNeverPersistsLocalSessionHandoff() {
        allow();
        var proof=committedProof();
        when(repo.committedEvidence("admin","dryrun-key",COMMITTED_ID))
            .thenReturn(proof);
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Core unavailable")).when(claims).release(any());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->service.acknowledge("Bearer admin",request(),stagedReceipt()))
            .getStatusCode());
        verify(repo,never()).recordHandoff(any());
    }

}

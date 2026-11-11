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
    AcademicSessionCandidateService service=
        new AcademicSessionCandidateService(identity,readiness,core,repo);
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
    }
    @Test void wrongRoleNeverCallsReadinessOrWrites() {
        when(identity.currentPrincipal("Bearer tc")).thenReturn(
            new AcademicCoreClient.Principal("tc001","ROLE_TC"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer tc",request())).getStatusCode());
        verifyNoInteractions(readiness,repo);
    }
    @Test void incompleteHistoryNeverStages() {
        allow();
        when(readiness.verify()).thenThrow(
            new ResponseStatusException(HttpStatus.CONFLICT,"History incomplete"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",request())).getStatusCode());
        verifyNoInteractions(repo);
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
    }
    @Test void malformedTimesFailBeforeCoreOrLocalSql() {
        var bad=new AcademicSessionCandidateService.Request(
            "key","class-1","lesson-1",5,3,"RESCHEDULE",
            "2027-02-12","13:00","09:00","PLANNED");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",bad)).getStatusCode());
        verifyNoInteractions(identity,readiness,core,repo);
    }
    @Test void rescheduleCannotInventCanceledState() {
        var bad=new AcademicSessionCandidateService.Request(
            "key","class-1","lesson-1",5,3,"RESCHEDULE",
            "2027-02-12","09:00","11:00","CANCELLED");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.stage("Bearer admin",bad)).getStatusCode());
        verifyNoInteractions(identity,readiness,core,repo);
    }
}

package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterStudentDetailReadServiceTest {
    final AcademicCoreClient core=mock(AcademicCoreClient.class);
    final AcademicRosterReadiness parity=mock(AcademicRosterReadiness.class);
    final AcademicRosterSourceRepository local=mock(AcademicRosterSourceRepository.class);
    final ObjectMapper json=new ObjectMapper();
    final AcademicRosterReadiness.Evidence consistent=
        new AcademicRosterReadiness.Evidence(4,7,1,1,4);

    final String coreBody="{\"studentId\":\"student-1\",\"studentCode\":\"S01\","+
        "\"targets\":[{\"id\":\"core-target\"}],"+
        "\"results\":[{\"id\":\"core-result\"}],"+
        "\"classes\":[{\"classId\":\"class-1\"}],"+
        "\"auditLogs\":[{\"id\":\"audit-1\"}]}";

    AcademicRosterStudentDetailReadService service(boolean targets,boolean results) {
        return new AcademicRosterStudentDetailReadService(
            core,parity,local,json,targets,results);
    }
    void admin() {
        when(core.studentDetail("student-1","Bearer admin")).thenReturn(coreBody);
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(parity.verify()).thenReturn(consistent);
        when(local.adminStudentTargetsJson("student-1")).thenReturn(
            "[{\"id\":\"academic-target\",\"courseId\":\"course-1\",\"targetValue\":7.5}]");
        when(local.adminStudentResultsJson("student-1")).thenReturn(
            "[{\"id\":\"academic-result\",\"classId\":\"class-1\",\"score\":9}]");
    }

    @Test void disabledFlagsPreserveCoreResponseAndDoNotAccessAcademic() throws Exception {
        when(core.studentDetail("student-1","Bearer user")).thenReturn(coreBody);
        var result=service(false,false).detail("student-1","Bearer user");
        assertEquals(json.readTree(coreBody),result);
        verifyNoInteractions(parity,local);
        verify(core,never()).currentPrincipal(anyString());
    }

    @Test void adminTargetFlagOnlyReplacesTargetField() {
        admin();
        var result=service(true,false).detail("student-1","Bearer admin");
        assertEquals("academic-target",result.path("targets").get(0).path("id").asText());
        assertEquals("core-result",result.path("results").get(0).path("id").asText());
        assertEquals("class-1",result.path("classes").get(0).path("classId").asText());
        verify(local,never()).adminStudentResultsJson(anyString());
        verify(parity,times(2)).verify();
    }

    @Test void adminResultFlagOnlyReplacesResultsField() {
        admin();
        var result=service(false,true).detail("student-1","Bearer admin");
        assertEquals("core-target",result.path("targets").get(0).path("id").asText());
        assertEquals("academic-result",result.path("results").get(0).path("id").asText());
        verify(local,never()).adminStudentTargetsJson(anyString());
        verify(parity,times(2)).verify();
    }

    @Test void bothFlagsReadSeparateAcademicProjectionsWhilePreservingAudit() {
        admin();
        var result=service(true,true).detail("student-1","Bearer admin");
        assertEquals("academic-target",result.path("targets").get(0).path("id").asText());
        assertEquals("academic-result",result.path("results").get(0).path("id").asText());
        assertEquals("audit-1",result.path("auditLogs").get(0).path("id").asText());
        verify(parity,times(2)).verify();
    }

    @Test void teacherMustUseCoreDetailEvenWhenBothFlagsEnabled() throws Exception {
        when(core.studentDetail("student-1","Bearer teacher")).thenReturn(coreBody);
        when(core.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("teacher","ROLE_TEACHER"));
        var result=service(true,true).detail("student-1","Bearer teacher");
        assertEquals(json.readTree(coreBody),result);
        verifyNoInteractions(local,parity);
    }

    @Test void revokedOrUnauthorizedTeacherNeverAccessesIndependentPII() {
        when(core.studentDetail("student-1","Bearer expired")).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN,"class access denied"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,()->
                service(true,true).detail("student-1","Bearer expired")).getStatusCode());
        verifyNoInteractions(parity,local);
    }

    @Test void wrongCoreStudentIdentityIsRejectedBeforeAnyAcademicRead() {
        when(core.studentDetail("student-1","Bearer admin")).thenReturn(
            coreBody.replace("student-1","student-2"));
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(
            ResponseStatusException.class,()->
                service(true,true).detail("student-1","Bearer admin")).getStatusCode());
        verifyNoInteractions(parity,local);
    }

    @Test void sourceRevisionRacingDuringAdminDetailFailsClosed() {
        admin();
        when(parity.verify()).thenReturn(consistent,
            new AcademicRosterReadiness.Evidence(5,8,1,1,4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->
                service(true,true).detail("student-1","Bearer admin")).getStatusCode());
    }

    @Test void missingAcademicProjectionIsNotSilentlyReplacedByCore() {
        admin();
        when(local.adminStudentTargetsJson("student-1")).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Academic DB down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->
                service(true,true).detail("student-1","Bearer admin")).getStatusCode());
        verify(local,never()).adminStudentResultsJson(anyString());
    }
}

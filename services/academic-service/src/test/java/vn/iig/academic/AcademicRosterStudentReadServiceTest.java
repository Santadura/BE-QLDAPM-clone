package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterStudentReadServiceTest {
    final AcademicCoreClient core=mock(AcademicCoreClient.class);
    final AcademicRosterReadiness parity=mock(AcademicRosterReadiness.class);
    final AcademicRosterSourceRepository local=mock(AcademicRosterSourceRepository.class);
    final ObjectMapper json=new ObjectMapper();
    final AcademicRosterReadiness.Evidence fresh=
        new AcademicRosterReadiness.Evidence(2,3,1,1,2);

    AcademicRosterStudentReadService read(boolean enabled) {
        return new AcademicRosterStudentReadService(core,parity,local,json,enabled);
    }
    @Test void disabledFlagAlwaysUsesExistingCoreGet() {
        when(core.students("Bearer admin")).thenReturn("[]");
        assertTrue(read(false).list("Bearer admin").isArray());
        verifyNoInteractions(parity,local);
        verify(core,never()).currentPrincipal(anyString());
    }
    @Test void adminReadsOnlyIndependentProjectionAfterDoubleProof() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(parity.verify()).thenReturn(fresh);
        when(local.adminStudentListJson()).thenReturn(
            "[{\"studentId\":\"student-1\",\"studentCode\":\"S1\"}]");
        assertEquals("student-1",
            read(true).list("Bearer admin").get(0).get("studentId").asText());
        verify(parity,times(2)).verify();
        verify(core,never()).students(anyString());
    }
    @Test void teacherContinuesCoreScopedStudentRead() {
        when(core.currentPrincipal("Bearer teacher")).thenReturn(
            new AcademicCoreClient.Principal("t1","ROLE_TEACHER"));
        when(core.students("Bearer teacher")).thenReturn("[]");
        assertTrue(read(true).list("Bearer teacher").isArray());
        verifyNoInteractions(parity,local);
    }
    @Test void coreRejectingTeacherPreventsAnyLocalPIIRead() {
        when(core.currentPrincipal("Bearer blocked")).thenReturn(
            new AcademicCoreClient.Principal("t1","ROLE_TEACHER"));
        when(core.students("Bearer blocked")).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN,"no class ACL"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,()->
                read(true).list("Bearer blocked")).getStatusCode());
        verifyNoInteractions(parity,local);
    }
    @Test void racingAcademicProofNeverServesPreviouslyReadPII() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(parity.verify()).thenReturn(fresh,
            new AcademicRosterReadiness.Evidence(3,4,1,1,2));
        when(local.adminStudentListJson()).thenReturn("[]");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->
                read(true).list("Bearer admin")).getStatusCode());
    }
    @Test void invalidOrRevokedAdminCannotReadLocalStudents() {
        when(core.currentPrincipal("Bearer expired")).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED,"expired"));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->
                read(true).list("Bearer expired")).getStatusCode());
        verifyNoInteractions(parity,local);
    }
}

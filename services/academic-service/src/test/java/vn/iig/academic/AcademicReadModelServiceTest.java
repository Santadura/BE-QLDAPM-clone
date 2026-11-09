package vn.iig.academic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AcademicReadModelServiceTest {
    private final AcademicCoreClient core=mock(AcademicCoreClient.class);
    private final AcademicSnapshotRepository repo=mock(AcademicSnapshotRepository.class);
    private final AcademicReadModelService model=
        new AcademicReadModelService(core,repo,new ObjectMapper());

    @Test void authorizedManagerReadsLivePlanningAndMaterializesOwnSnapshot() {
        when(core.currentPrincipal("bearer")).thenReturn(
            new AcademicCoreClient.Principal("cm001","ROLE_CM"));
        when(core.planning("bearer")).thenReturn(
            "[{\"classId\":\"class-1\",\"classCode\":\"A-10\"}]");
        when(repo.save(anyString(),eq("cm001"),eq("ROLE_CM"),
            eq("CLASS_PLANNING"),contains("class-1")))
            .thenReturn(Instant.parse("2026-11-10T08:00:00Z"));
        var result=model.planning("bearer");
        assertTrue(result.payload().isArray());
        assertEquals("class-1",result.payload().get(0).path("classId").asText());
        assertEquals(Instant.parse("2026-11-10T08:00:00Z"),
            result.synchronizedAt());
        verify(core).planning("bearer");
        verify(repo).save(argThat(s->s.length()==64),eq("cm001"),eq("ROLE_CM"),
            eq("CLASS_PLANNING"),anyString());
    }

    @Test void workerCannotReadEveryClassPlanningDocument() {
        when(core.currentPrincipal("bearer")).thenReturn(
            new AcademicCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->model.planning("bearer"))
                .getStatusCode());
        verify(core,never()).planning(anyString());
        verifyNoInteractions(repo);
    }

    @Test void plannedSessionsRetainCoreLessonAccessControl() {
        when(core.currentPrincipal("bearer")).thenReturn(
            new AcademicCoreClient.Principal("cs001","ROLE_CS"));
        when(core.plannedSessions("class-1","bearer")).thenReturn(
            "[{\"id\":\"lesson-1\",\"status\":\"PLANNED\",\"startTime\":\"09:00\"}]");
        when(repo.save(anyString(),eq("cs001"),eq("ROLE_CS"),
            eq("PLANNED_SESSIONS"),anyString()))
            .thenReturn(Instant.parse("2026-11-10T08:00:00Z"));
        assertEquals("lesson-1",model.sessions("class-1","bearer")
            .payload().get(0).path("id").asText());
        verify(core).plannedSessions("class-1","bearer");
    }

    @Test void sourceOutageNeverServesCachedStudentOrClassData() {
        when(core.currentPrincipal("bearer")).thenReturn(
            new AcademicCoreClient.Principal("cm001","ROLE_CM"));
        when(core.planning("bearer")).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core disconnected"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,()->model.planning("bearer"))
                .getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test void malformedSourceArrayNeverPoisonsAcademicReadDatabase() {
        when(core.currentPrincipal("bearer")).thenReturn(
            new AcademicCoreClient.Principal("cm001","ROLE_CM"));
        when(core.planning("bearer")).thenReturn("{\"unexpected\":true}");
        assertEquals(HttpStatus.BAD_GATEWAY,
            assertThrows(ResponseStatusException.class,()->model.planning("bearer"))
                .getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test void invalidClassIdIsRejectedBeforeRemoteIdentityLookup() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->model.sessions("","bearer")).getStatusCode());
        verifyNoInteractions(core,repo);
    }
}

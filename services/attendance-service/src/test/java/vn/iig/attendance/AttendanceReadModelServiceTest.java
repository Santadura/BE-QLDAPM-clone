package vn.iig.attendance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AttendanceReadModelServiceTest {
    private final AttendanceCoreClient core=mock(AttendanceCoreClient.class);
    private final AttendanceSnapshotRepository repo=mock(AttendanceSnapshotRepository.class);
    private final AttendanceEventProjectionRepository source=
        mock(AttendanceEventProjectionRepository.class);
    private final AttendanceEventRelay relay=mock(AttendanceEventRelay.class);
    private final AttendanceReadModelService service=
        new AttendanceReadModelService(core,repo,source,relay,new ObjectMapper());

    @Test void assignedCsSeesExactTeacherAttendanceShapeFromLiveSource() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token"))
            .thenReturn("[{\"id\":\"teaching-001\",\"teacherName\":\"Teacher\",\"assignedToMe\":true,\"attendanceStatus\":null}]");
        when(repo.save(anyString(),eq("cs001"),eq("class-0001"),anyString()))
            .thenReturn(Instant.parse("2026-11-10T02:00:00Z"));
        var result=service.list("class-0001","Bearer token");
        assertEquals("teaching-001",result.payload().get(0).path("id").asText());
        assertTrue(result.payload().get(0).path("assignedToMe").asBoolean());
        assertEquals(Instant.parse("2026-11-10T02:00:00Z"),result.synchronizedAt());
        verify(core).teacherAttendance("class-0001","Bearer token");
        verify(repo).save(argThat(s->s.length()==64),
            eq("cs001"),eq("class-0001"),contains("teaching-001"));
    }

    @Test void importedAttendanceStatusIsUsedForAuthorizedCsRead() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token")).thenReturn(
            "[{\"id\":\"teaching-001\",\"attendanceStatus\":\"PRESENT\"}]");
        when(source.stateFor("teaching-001")).thenReturn(
            new AttendanceEventProjectionRepository.AttendanceState("UPSERT","PRESENT"));
        when(repo.save(anyString(),eq("cs001"),eq("class-0001"),anyString()))
            .thenReturn(Instant.parse("2026-11-10T02:00:00Z"));
        var result=service.list("class-0001","Bearer token");
        assertEquals("PRESENT",result.payload().get(0).path("attendanceStatus").asText());
        var order=inOrder(core,relay,source,repo);
        order.verify(core).teacherAttendance("class-0001","Bearer token");
        order.verify(relay).requireCaughtUp();
        order.verify(source).stateFor("teaching-001");
        order.verify(repo).save(anyString(),eq("cs001"),eq("class-0001"),anyString());
    }

    @Test void staleProjectedAttendanceCannotBeServedAsIfItWereCurrent() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token")).thenReturn(
            "[{\"id\":\"teaching-001\",\"attendanceStatus\":\"PRESENT\"}]");
        when(source.stateFor("teaching-001")).thenReturn(
            new AttendanceEventProjectionRepository.AttendanceState("UPSERT","ABSENT"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,
                ()->service.list("class-0001","Bearer token")).getStatusCode());
        verify(core,times(2)).teacherAttendance("class-0001","Bearer token");
        verify(relay,times(2)).requireCaughtUp();
        verify(repo,never()).save(anyString(),anyString(),anyString(),anyString());
    }

    @Test void importedDeletionTombstoneRestoresNullAttendanceStatus() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token")).thenReturn(
            "[{\"id\":\"teaching-001\",\"attendanceStatus\":null}]");
        when(source.stateFor("teaching-001")).thenReturn(
            new AttendanceEventProjectionRepository.AttendanceState("DELETE","PRESENT"));
        when(repo.save(anyString(),anyString(),anyString(),anyString()))
            .thenReturn(Instant.parse("2026-11-10T02:00:00Z"));
        assertTrue(service.list("class-0001","Bearer token").payload()
            .get(0).path("attendanceStatus").isNull());
    }

    @Test void teacherCannotReadPrivateCsAttendance() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("teacher001","ROLE_TEACHER"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->service.list("class-0001","Bearer token")).getStatusCode());
        verify(core,never()).teacherAttendance(anyString(),anyString());
        verifyNoInteractions(repo);
    }

    @Test void unassignedCsIsDeniedByLiveCoreAndNoSnapshotIsRead() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs002","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token"))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Not assigned to this class"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->service.list("class-0001","Bearer token")).getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test void sourceOutageFailsClosedEvenWhenLastSnapshotExists() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token"))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core unavailable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,
                ()->service.list("class-0001","Bearer token")).getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test void malformedCoreJsonCannotPoisonAttendanceDb() {
        when(core.currentPrincipal("Bearer token")).thenReturn(
            new AttendanceCoreClient.Principal("cs001","ROLE_CS"));
        when(core.teacherAttendance("class-0001","Bearer token"))
            .thenReturn("{\"notAnArray\":true}");
        assertEquals(HttpStatus.BAD_GATEWAY,
            assertThrows(ResponseStatusException.class,
                ()->service.list("class-0001","Bearer token")).getStatusCode());
        verifyNoInteractions(repo);
    }

    @Test void invalidClassIdIsRejectedBeforeTokenLookup() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.list("","Bearer token")).getStatusCode());
        verifyNoInteractions(core,repo);
    }
}

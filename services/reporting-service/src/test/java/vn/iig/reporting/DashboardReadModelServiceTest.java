package vn.iig.reporting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DashboardReadModelServiceTest {
    private final ReportingCoreClient core = mock(ReportingCoreClient.class);
    private final DashboardRepository repo = mock(DashboardRepository.class);
    private final DashboardReadModelService service =
        new DashboardReadModelService(repo, core, new ObjectMapper(), 10);

    @Test void isolatesDashboardCacheByUsernameRoleAndDay() {
        var admin = new ReportingCoreClient.Principal("admin","ROLE_ADMIN");
        var teacher = new ReportingCoreClient.Principal("admin","ROLE_TEACHER");
        LocalDate today = LocalDate.of(2026,11,9);
        assertNotEquals(DashboardReadModelService.key(admin,today),
            DashboardReadModelService.key(teacher,today));
        assertNotEquals(DashboardReadModelService.key(admin,today),
            DashboardReadModelService.key(admin,today.plusDays(1)));
        assertNotEquals(DashboardReadModelService.key(admin,today),
            DashboardReadModelService.key(
                new ReportingCoreClient.Principal("admin2","ROLE_ADMIN"),today));
    }

    @Test void revalidatesIdentityBeforeReadingEvenFreshCache() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new ReportingCoreClient.Principal("admin","ROLE_ADMIN"));
        when(repo.get(anyString())).thenReturn(new DashboardRepository.Snapshot(
            "{\"role\":\"ADMIN\",\"stats\":[]}",Instant.now()));
        var result = service.load("Bearer admin");
        assertEquals("ADMIN",result.payload().get("role").asText());
        verify(core).currentPrincipal("Bearer admin");
        verify(core,never()).currentDashboard(anyString());
    }

    @Test void deniesInvalidOrUnsupportedRoleBeforeCache() {
        when(core.currentPrincipal("Bearer invalid")).thenThrow(
            new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class, () -> service.load("Bearer invalid"));
        when(core.currentPrincipal("Bearer guest")).thenReturn(
            new ReportingCoreClient.Principal("guest","ROLE_USER"));
        assertThrows(ResponseStatusException.class, () -> service.load("Bearer guest"));
        verifyNoInteractions(repo);
    }

    @Test void refreshesExpiredDataAndPersistsResult() {
        when(core.currentPrincipal("Bearer teacher")).thenReturn(
            new ReportingCoreClient.Principal("teacher001","ROLE_TEACHER"));
        when(repo.get(anyString())).thenReturn(new DashboardRepository.Snapshot(
            "{\"role\":\"TEACHER\",\"stale\":true}", Instant.now().minusSeconds(100)));
        when(core.currentDashboard("teacher001")).thenReturn("{\"role\":\"TEACHER\",\"stats\":[]}");
        when(repo.put(anyString(),anyString())).thenReturn(new DashboardRepository.Snapshot(
            "{\"role\":\"TEACHER\",\"stats\":[]}",Instant.now()));
        var result = service.load("Bearer teacher");
        assertEquals("TEACHER",result.payload().get("role").asText());
        verify(core).currentDashboard("teacher001");
        verify(repo).put(anyString(),eq("{\"role\":\"TEACHER\",\"stats\":[]}"));
    }
}

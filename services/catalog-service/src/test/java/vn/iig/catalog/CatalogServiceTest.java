package vn.iig.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CatalogServiceTest {
    private final CatalogRepository repository = mock(CatalogRepository.class);
    private final CoreClient core = mock(CoreClient.class);
    private final CatalogService service = new CatalogService(repository, core);

    @Test void rejectsRequestsBeforeBootstrap() {
        when(repository.imported()).thenReturn(false);
        assertThrows(ResponseStatusException.class, service::active);
        verify(repository, never()).active();
    }

    @Test void bootstrapsFromLegacyAndProjectsToAcademicReadModel() {
        Course old = new Course("course-ielts", "IELTS", "ACTIVE");
        when(repository.imported()).thenReturn(false, true);
        when(core.exportLegacy()).thenReturn(List.of(old));
        when(repository.pending(100)).thenReturn(
            List.of(new CatalogRepository.Pending(old, 1)));
        service.synchronize();
        verify(repository).importInitial(List.of(old));
        verify(core).project(old);
        verify(repository).acknowledge("course-ielts", 1L);
    }

    @Test void leavesFailedProjectionPendingForRetry() {
        Course changed = new Course("course-ielts", "IELTS Updated", "ACTIVE");
        when(repository.imported()).thenReturn(true);
        when(repository.pending(100)).thenReturn(
            List.of(new CatalogRepository.Pending(changed, 2)));
        doThrow(new IllegalStateException("core unavailable")).when(core).project(changed);
        service.synchronize();
        verify(repository, never()).acknowledge(anyString(), anyLong());
    }

    @Test void forbidsChangingCourseId() {
        when(repository.imported()).thenReturn(true);
        assertThrows(ResponseStatusException.class,
            () -> service.update("course-one", new Course("course-two", "Two", "ACTIVE")));
        verify(repository, never()).update(anyString(), any(Course.class));
    }
}

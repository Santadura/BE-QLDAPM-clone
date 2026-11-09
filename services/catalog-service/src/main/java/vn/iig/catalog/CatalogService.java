package vn.iig.catalog;

import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalog owns write state. An explicit one-time import moves existing data
 * from monolith; the core course rows are thereafter replicated references.
 */
@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private final CatalogRepository repository;
    private final CoreClient core;

    public CatalogService(CatalogRepository repository, CoreClient core) {
        this.repository = repository;
        this.core = core;
    }

    public void requireReady() {
        if (!repository.imported()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Course catalog initial import has not completed");
        }
    }

    public List<Course> active() {
        requireReady();
        return repository.active();
    }

    public List<Course> all() {
        requireReady();
        return repository.all();
    }

    public Course create(@Valid Course course) {
        requireReady();
        return repository.create(normalize(course));
    }

    public Course update(String courseId, @Valid Course value) {
        requireReady();
        if (!courseId.equals(value.courseId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Course ID cannot change");
        }
        return repository.update(courseId, normalize(value));
    }

    private Course normalize(Course course) {
        return new Course(course.courseId().trim(), course.name().trim(),
            course.status().trim().toUpperCase(Locale.ROOT));
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 4000)
    public void synchronize() {
        if (!repository.imported()) {
            try {
                List<Course> existing = core.exportLegacy();
                repository.importInitial(existing);
                log.info("Imported {} courses from the legacy academic database", existing.size());
            } catch (Exception ex) {
                log.warn("Catalog initial import not ready yet: {}", ex.getMessage());
                return;
            }
        }
        for (CatalogRepository.Pending pending : repository.pending(100)) {
            try {
                core.project(pending.course());
                repository.acknowledge(pending.course().courseId(), pending.version());
            } catch (Exception ex) {
                log.warn("Catalog projection pending for {}: {}",
                    pending.course().courseId(), ex.getMessage());
                return;
            }
        }
    }
}

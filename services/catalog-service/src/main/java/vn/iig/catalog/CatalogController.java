package vn.iig.catalog;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Preserve existing FE GET /api/classes/courses response schema, but move
 * its authoritative read to the independent catalog database.
 */
@RestController
public class CatalogController {
    private final CatalogService service;
    private final CoreClient auth;

    public CatalogController(CatalogService service, CoreClient auth) {
        this.service = service;
        this.auth = auth;
    }

    public record CourseOption(String courseId, String name) {}

    @GetMapping("/api/classes/courses")
    public List<CourseOption> options(@RequestHeader(value="Authorization", required=false) String token) {
        auth.authenticate(token);
        return service.active().stream().map(c -> new CourseOption(c.courseId(), c.name())).toList();
    }

    @GetMapping("/api/catalog/courses")
    public List<Course> all(@RequestHeader(value="Authorization", required=false) String token) {
        auth.requireAdmin(token);
        return service.all();
    }

    @PostMapping("/api/catalog/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public Course create(@RequestHeader(value="Authorization", required=false) String token,
                         @Valid @RequestBody Course course) {
        auth.requireAdmin(token);
        return service.create(course);
    }

    @PutMapping("/api/catalog/courses/{courseId}")
    public Course update(@RequestHeader(value="Authorization", required=false) String token,
                         @PathVariable String courseId,
                         @Valid @RequestBody Course course) {
        auth.requireAdmin(token);
        return service.update(courseId, course);
    }
}

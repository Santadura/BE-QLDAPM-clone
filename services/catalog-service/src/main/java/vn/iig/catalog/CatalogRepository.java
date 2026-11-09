package vn.iig.catalog;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class CatalogRepository {
    private final JdbcTemplate db;

    public record Pending(Course course, long version) {}

    public CatalogRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean imported() {
        Integer found = db.queryForObject(
            "SELECT count(*) FROM catalog_meta WHERE key='initial-import-complete'",
            Integer.class);
        return found != null && found > 0;
    }

    public List<Course> active() {
        return db.query("SELECT course_id,name,status FROM catalog_courses " +
                "WHERE status='ACTIVE' ORDER BY name,course_id",
            (rs, i) -> new Course(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public List<Course> all() {
        return db.query("SELECT course_id,name,status FROM catalog_courses ORDER BY name,course_id",
            (rs, i) -> new Course(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    @Transactional
    public void importInitial(List<Course> rows) {
        if (imported()) return;
        for (Course course : rows) {
            // Do not overwrite a catalog modification if initial import is retried.
            int inserted = db.update("INSERT INTO catalog_courses(course_id,name,status) " +
                "VALUES(?,?,?) ON CONFLICT(course_id) DO NOTHING",
                course.courseId(), course.name(), course.status());
            if (inserted == 1) {
                db.update("INSERT INTO catalog_outbox(course_id,version) VALUES(?,1) " +
                    "ON CONFLICT(course_id) DO NOTHING", course.courseId());
            }
        }
        db.update("INSERT INTO catalog_meta(key,value) VALUES('initial-import-complete','yes') " +
            "ON CONFLICT(key) DO NOTHING");
    }

    @Transactional
    public Course create(Course value) {
        try {
            db.update("INSERT INTO catalog_courses(course_id,name,status) VALUES(?,?,?)",
                value.courseId(), value.name(), value.status());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Course ID already exists");
        }
        markPending(value.courseId(), 1);
        return value;
    }

    @Transactional
    public Course update(String courseId, Course value) {
        Long version = db.query(
            "SELECT version FROM catalog_courses WHERE course_id=? FOR UPDATE",
            rs -> rs.next() ? rs.getLong(1) : null, courseId);
        if (version == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        long next = version + 1;
        db.update("UPDATE catalog_courses SET name=?,status=?,version=?,updated_at=NOW() " +
            "WHERE course_id=?", value.name(), value.status(), next, courseId);
        markPending(courseId, next);
        return new Course(courseId, value.name(), value.status());
    }

    private void markPending(String id, long version) {
        db.update("INSERT INTO catalog_outbox(course_id,version,delivered_at) VALUES(?,?,NULL) " +
            "ON CONFLICT(course_id) DO UPDATE SET version=EXCLUDED.version,delivered_at=NULL",
            id, version);
    }

    public List<Pending> pending(int limit) {
        return db.query("SELECT c.course_id,c.name,c.status,o.version " +
            "FROM catalog_outbox o JOIN catalog_courses c ON c.course_id=o.course_id " +
            "WHERE o.delivered_at IS NULL ORDER BY c.course_id LIMIT ?",
            (rs, i) -> new Pending(new Course(rs.getString(1), rs.getString(2),
                rs.getString(3)), rs.getLong(4)), limit);
    }

    public void acknowledge(String courseId, long version) {
        db.update("UPDATE catalog_outbox SET delivered_at=NOW() " +
            "WHERE course_id=? AND version=? AND delivered_at IS NULL", courseId, version);
    }
}

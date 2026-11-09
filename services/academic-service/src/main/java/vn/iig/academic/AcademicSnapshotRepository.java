package vn.iig.academic;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Scoped durable Academic GET materialization in its OWN PostgreSQL. */
@Repository
public class AcademicSnapshotRepository {
    private final JdbcTemplate db;

    public AcademicSnapshotRepository(JdbcTemplate db) {
        this.db=db;
    }

    @Transactional
    public Instant save(String key,String username,String role,String kind,String json) {
        Timestamp saved=db.queryForObject(
            "INSERT INTO academic_read_snapshots " +
            "(snapshot_key,username,role_id,resource_kind,payload,synchronized_at) " +
            "VALUES(?,?,?,?,CAST(? AS jsonb),NOW()) ON CONFLICT(snapshot_key) " +
            "DO UPDATE SET payload=EXCLUDED.payload,synchronized_at=NOW() " +
            "RETURNING synchronized_at",
            Timestamp.class,key,username,role,kind,json);
        if(saved==null) throw new IllegalStateException("Academic read projection not persisted");
        return saved.toInstant();
    }
}

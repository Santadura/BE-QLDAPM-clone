package vn.iig.academic;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Scoped durable Academic GET materialization in its OWN PostgreSQL. */
@Repository
public class AcademicSnapshotRepository {
    public record PlanningSnapshot(long sourceRevision,String payload,Instant synchronizedAt) {}
    private final JdbcTemplate db;

    public AcademicSnapshotRepository(JdbcTemplate db) {
        this.db=db;
    }

    public PlanningSnapshot loadPlanning() {
        var rows=db.query(
            "SELECT source_revision,payload::text,synchronized_at " +
            "FROM academic_planning_projection WHERE name='planning'",
            (rs,i)->new PlanningSnapshot(rs.getLong(1),rs.getString(2),
                rs.getTimestamp(3).toInstant()));
        return rows.isEmpty()?null:rows.get(0);
    }

    @Transactional
    public PlanningSnapshot savePlanning(long revision,String payload) {
        if(revision<1 || payload==null || payload.isBlank())
            throw new IllegalArgumentException("Invalid Academic revision");
        // Concurrent GETs cannot overwrite a newer snapshot with an older
        // Core source version. Both branches return the committed local row.
        db.update(
            "INSERT INTO academic_planning_projection " +
            "(name,source_revision,payload,synchronized_at) " +
            "VALUES('planning',?,CAST(? AS jsonb),NOW()) " +
            "ON CONFLICT(name) DO UPDATE SET " +
            "source_revision=EXCLUDED.source_revision,payload=EXCLUDED.payload," +
            "synchronized_at=NOW() WHERE " +
            "academic_planning_projection.source_revision<EXCLUDED.source_revision",
            revision,payload);
        PlanningSnapshot saved=loadPlanning();
        if(saved==null || saved.sourceRevision()<revision)
            throw new IllegalStateException("Academic planning projection not advanced");
        return saved;
    }


    public record SessionSnapshot(long sourceRevision,String payload,Instant synchronizedAt) {}

    public SessionSnapshot loadSessions(String classId) {
        var rows=db.query(
            "SELECT source_revision,payload::text,synchronized_at " +
            "FROM academic_session_projections WHERE class_id=?",
            (rs,i)->new SessionSnapshot(rs.getLong(1),rs.getString(2),
                rs.getTimestamp(3).toInstant()),classId);
        return rows.isEmpty()?null:rows.get(0);
    }

    @Transactional
    public SessionSnapshot saveSessions(String classId,long revision,String payload) {
        if(classId==null || classId.isBlank() || classId.length()>50 ||
                revision<1 || payload==null || payload.isBlank())
            throw new IllegalArgumentException("Invalid Academic session projection");
        // An older concurrently fetched snapshot can never overwrite a
        // newer version that has already committed to Academic PostgreSQL.
        db.update(
            "INSERT INTO academic_session_projections " +
            "(class_id,source_revision,payload,synchronized_at) " +
            "VALUES(?,?,CAST(? AS jsonb),NOW()) " +
            "ON CONFLICT(class_id) DO UPDATE SET " +
            "source_revision=EXCLUDED.source_revision,payload=EXCLUDED.payload," +
            "synchronized_at=NOW() WHERE " +
            "academic_session_projections.source_revision<EXCLUDED.source_revision",
            classId,revision,payload);
        SessionSnapshot saved=loadSessions(classId);
        if(saved==null || saved.sourceRevision()<revision)
            throw new IllegalStateException("Academic lesson projection not advanced");
        return saved;
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

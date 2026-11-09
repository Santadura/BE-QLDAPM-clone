package vn.iig.attendance;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Actor-scoped durable GET snapshot. Never authorize from this table:
 * the live Core identity and class ACL must both pass before serving data.
 */
@Repository
public class AttendanceSnapshotRepository {
    private final JdbcTemplate db;
    public AttendanceSnapshotRepository(JdbcTemplate db) {this.db=db;}

    public Instant save(String key,String username,String classId,String json) {
        Timestamp at=db.queryForObject(
            "INSERT INTO attendance_teacher_read_snapshots " +
            "(snapshot_key,actor_username,class_id,payload,synchronized_at) " +
            "VALUES(?,?,?,CAST(? AS jsonb),NOW()) " +
            "ON CONFLICT(snapshot_key) DO UPDATE SET " +
            "payload=EXCLUDED.payload,synchronized_at=NOW() RETURNING synchronized_at",
            Timestamp.class,key,username,classId,json);
        if(at==null) throw new IllegalStateException("Attendance snapshot not persisted");
        return at.toInstant();
    }
}

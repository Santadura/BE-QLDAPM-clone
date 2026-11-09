package vn.iig.reporting;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Private role-scoped read model; does not join another service's database. */
@Repository
public class DashboardRepository {
    public record Snapshot(String payload, Instant refreshedAt) {}
    private final JdbcTemplate db;

    public DashboardRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Snapshot get(String key) {
        List<Snapshot> rows = db.query(
            "SELECT payload::text,refreshed_at FROM dashboard_snapshots WHERE snapshot_key=?",
            (rs,i) -> new Snapshot(rs.getString(1), rs.getTimestamp(2).toInstant()), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Snapshot put(String key, String json) {
        db.update("INSERT INTO dashboard_snapshots(snapshot_key,payload,refreshed_at) " +
            "VALUES(?,CAST(? AS jsonb),NOW()) ON CONFLICT(snapshot_key) DO UPDATE " +
            "SET payload=EXCLUDED.payload,refreshed_at=EXCLUDED.refreshed_at",
            key,json);
        return get(key);
    }
}

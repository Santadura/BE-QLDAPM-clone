package vn.iig.reporting;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Exclusive read-model storage for the Reporting microservice. */
@Repository
public class SnapshotRepository {
    public record Snapshot(String payload, Instant refreshedAt) {}

    private final JdbcTemplate db;

    public SnapshotRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Snapshot get(String key) {
        List<Snapshot> rows = db.query(
            "SELECT payload::text, refreshed_at FROM statistics_snapshots WHERE snapshot_key=?",
            (rs, index) -> new Snapshot(rs.getString(1), rs.getTimestamp(2).toInstant()), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Snapshot put(String key, String json) {
        db.update("INSERT INTO statistics_snapshots(snapshot_key,payload,refreshed_at) " +
            "VALUES (?,CAST(? AS jsonb),NOW()) ON CONFLICT(snapshot_key) DO UPDATE " +
            "SET payload=EXCLUDED.payload, refreshed_at=EXCLUDED.refreshed_at", key, json);
        return get(key);
    }
}

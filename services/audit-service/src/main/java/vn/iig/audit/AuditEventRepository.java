package vn.iig.audit;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditEventRepository {
    private final JdbcTemplate db;

    public AuditEventRepository(JdbcTemplate db) {
        this.db = db;
    }

    // The upstream outbox retries. The event ID is the idempotency key.
    public void insertIfAbsent(AuditEvent event) {
        db.update("INSERT INTO audit_events (event_id,username,action,entity_type,entity_id,description,occurred_at) " +
                  "VALUES (?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING",
            event.eventId(), event.username(), event.action(), event.entityType(),
            event.entityId(), event.description(), Timestamp.from(event.occurredAt()));
    }

    public List<AuditEvent> recent(int limit) {
        return db.query("SELECT event_id,username,action,entity_type,entity_id,description,occurred_at " +
                "FROM audit_events ORDER BY occurred_at DESC,event_id DESC LIMIT ?",
            (rs, index) -> new AuditEvent(rs.getString("event_id"), rs.getString("username"),
                rs.getString("action"), rs.getString("entity_type"), rs.getString("entity_id"),
                rs.getString("description"), rs.getTimestamp("occurred_at").toInstant()), limit);
    }
}

-- Durable delivery acknowledgement for the audit-service projection.
-- Original audit_logs remain the transactional source, so outages cannot silently
-- discard an event. The relay retries until the destination accepts the event.
CREATE TABLE "audit_outbox_deliveries" (
  "audit_log_id" VARCHAR(50) NOT NULL PRIMARY KEY,
  "delivered_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "audit_outbox_deliveries_audit_log_id_fkey"
    FOREIGN KEY ("audit_log_id") REFERENCES "audit_logs"("audit_log_id")
    ON DELETE CASCADE ON UPDATE CASCADE
);

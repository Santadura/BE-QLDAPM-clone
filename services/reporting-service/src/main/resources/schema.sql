-- Reporting owns a private, durable read-model database. Legacy statistics
-- computations remain in core during the transition to event-driven views.
CREATE TABLE IF NOT EXISTS statistics_snapshots (
  snapshot_key VARCHAR(100) PRIMARY KEY,
  payload JSONB NOT NULL,
  refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS statistics_snapshots_refreshed
  ON statistics_snapshots (refreshed_at DESC);

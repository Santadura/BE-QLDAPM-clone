-- Reporting owns a private, durable read-model database. Legacy statistics
-- computations remain in core during the transition to event-driven views.
CREATE TABLE IF NOT EXISTS statistics_snapshots (
  snapshot_key VARCHAR(100) PRIMARY KEY,
  payload JSONB NOT NULL,
  refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS statistics_snapshots_refreshed
  ON statistics_snapshots (refreshed_at DESC);

-- Role-scoped dashboard snapshots. No cache entry is shared between users,
-- roles, or business dates; caller JWT is always checked before any read.
CREATE TABLE IF NOT EXISTS dashboard_snapshots (
  snapshot_key VARCHAR(128) PRIMARY KEY,
  payload JSONB NOT NULL,
  refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS dashboard_snapshots_refreshed
  ON dashboard_snapshots (refreshed_at DESC);

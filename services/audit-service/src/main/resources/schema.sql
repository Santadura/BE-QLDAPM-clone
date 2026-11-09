-- This service exclusively owns its audit_events database.
-- The core backend cannot read or modify this database directly.
CREATE TABLE IF NOT EXISTS audit_events (
  event_id VARCHAR(50) PRIMARY KEY,
  username VARCHAR(50),
  action VARCHAR(100) NOT NULL,
  entity_type VARCHAR(50) NOT NULL,
  entity_id VARCHAR(50) NOT NULL,
  description TEXT,
  occurred_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_events_time ON audit_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at DESC);

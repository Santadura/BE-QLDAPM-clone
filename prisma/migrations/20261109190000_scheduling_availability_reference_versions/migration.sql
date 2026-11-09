-- The migrated Scheduling service owns approval and registration.
-- Core retains this reference-only table because existing assignment,
-- payroll and class queries currently use core FKs and joins.
CREATE TABLE IF NOT EXISTS scheduling_availability_versions (
  availability_id VARCHAR(50) PRIMARY KEY,
  version BIGINT NOT NULL CHECK (version >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

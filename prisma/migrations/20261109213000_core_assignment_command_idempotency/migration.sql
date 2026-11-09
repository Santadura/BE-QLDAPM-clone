-- Phase 8b-2 transitional command boundary.
-- Core still owns assignment writes and class invariants; command outcomes
-- must be recorded in the SAME transaction as schedule mutations and audit.
-- The independent Scheduling service will take ownership only after the
-- Academic session/attendance contracts and compensation are implemented.
CREATE TABLE IF NOT EXISTS scheduling_command_receipts (
  actor_username VARCHAR(100) NOT NULL,
  command_type VARCHAR(160) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_payload JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY (actor_username,command_type,request_key),
  CONSTRAINT scheduling_command_receipt_complete
    CHECK ((completed_at IS NULL AND response_payload IS NULL)
        OR (completed_at IS NOT NULL AND response_payload IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS scheduling_command_receipts_created_idx
  ON scheduling_command_receipts(created_at);

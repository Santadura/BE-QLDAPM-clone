-- Phase 8e: immutable per-event Core acknowledgements.
-- The previous last-revision table alone cannot prove that an older
-- Scheduling event was applied once later revisions are committed.
-- This new table retains exact historical receipts without collapsing
-- the event stream; it is independent from Core's legacy assignment outbox.
CREATE TABLE IF NOT EXISTS scheduling_assignment_reference_event_receipts (
  event_id VARCHAR(50) PRIMARY KEY,
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('STAFF','TEACHING')),
  schedule_id VARCHAR(50) NOT NULL,
  revision BIGINT NOT NULL CHECK (revision>=1),
  payload_hash CHAR(64) NOT NULL,
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(40),
  request_key VARCHAR(128),
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(source_type,schedule_id,revision),
  CONSTRAINT scheduling_event_receipt_command_both CHECK (
    (operation IS NULL AND request_key IS NULL) OR
    (operation IS NOT NULL AND request_key IS NOT NULL)
  )
);
-- Safely bootstrap the last receipt of previous phase 8d-5 deployments.
-- Older events cannot be reconstructed and remain fail-closed; never forge
-- missing historical records. A nullable command identity identifies this
-- initial backfill and is NOT accepted as proof for event replay.
INSERT INTO scheduling_assignment_reference_event_receipts
  (event_id,source_type,schedule_id,revision,payload_hash,actor_username,applied_at)
SELECT last_event_id,source_type,schedule_id,last_revision,
       payload_hash,actor_username,applied_at
FROM scheduling_assignment_reference_receipts
ON CONFLICT DO NOTHING;

-- Event identity and payload are immutable after commit. A protected
-- application DB user should not have direct table mutation privileges;
-- the trigger additionally rejects accidental SQL updates or deletions.
CREATE OR REPLACE FUNCTION iig_guard_reference_event_receipt_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Committed Core assignment event receipts are immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_reference_receipt_immutable
  ON scheduling_assignment_reference_event_receipts;
CREATE TRIGGER trg_iig_reference_receipt_immutable
  BEFORE UPDATE OR DELETE ON scheduling_assignment_reference_event_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_guard_reference_event_receipt_immutable();

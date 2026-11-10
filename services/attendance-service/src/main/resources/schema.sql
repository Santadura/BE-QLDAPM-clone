-- Attendance GET projection. No writes of actual teacher attendance are
-- performed by this service until the attendance writer saga is ready.
-- Every row is actor-scoped; no class/teacher roster is shared between CSs.
CREATE TABLE IF NOT EXISTS attendance_teacher_read_snapshots (
  snapshot_key CHAR(64) PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attendance_teacher_actor
  ON attendance_teacher_read_snapshots(actor_username,class_id);

-- Independent Attendance source-of-fact read model. Core still owns writes
-- until class/session and Payroll dependencies have a verified cutover.
CREATE TABLE IF NOT EXISTS attendance_teacher_source_records (
  teaching_schedule_id VARCHAR(50) PRIMARY KEY,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS attendance_import_receipts (
  event_id BIGINT PRIMARY KEY CHECK(event_id>0),
  teaching_schedule_id VARCHAR(50) NOT NULL,
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attendance_import_source
  ON attendance_import_receipts(teaching_schedule_id,event_id);


-- Attendance service-owned durable command inbox. PENDING is not success.
-- The source of truth remains Core's teacher attendance table/transaction.
CREATE TABLE IF NOT EXISTS attendance_write_command_inbox (
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  state VARCHAR(12) NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','COMMITTED')),
  response_payload JSONB CHECK (response_payload IS NULL OR jsonb_typeof(response_payload)='object'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,class_id,teaching_schedule_id,request_key),
  CONSTRAINT attendance_inbox_completion CHECK (
    (state='PENDING' AND response_payload IS NULL AND completed_at IS NULL) OR
    (state='COMMITTED' AND response_payload IS NOT NULL AND completed_at IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_attendance_inbox_pending
  ON attendance_write_command_inbox(created_at) WHERE state='PENDING';


-- Phase 8f-8: write-model-shaped independent mirror, NOT a second writer.
-- Core remains the exclusive transactional attendance source. The importer
-- owns all writes here until a later fenced, audited writer handoff exists.
-- The full JSON payload retains timestamps used for future Payroll sourcing.
CREATE TABLE IF NOT EXISTS attendance_teacher_ledger (
  teaching_schedule_id VARCHAR(50) PRIMARY KEY,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  attendance_id VARCHAR(50),
  status VARCHAR(20),
  recorded_by VARCHAR(50),
  source_hash CHAR(64) NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT attendance_ledger_live_identity CHECK (
    (event_kind='UPSERT' AND attendance_id IS NOT NULL
      AND status IN ('PRESENT','ABSENT','LATE') AND recorded_by IS NOT NULL)
    OR
    (event_kind='DELETE' AND attendance_id IS NULL
      AND status IS NULL AND recorded_by IS NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_attendance_ledger_recorder
  ON attendance_teacher_ledger(recorded_by) WHERE event_kind='UPSERT';

-- Every historical source event, including previously acknowledged Core
-- events and DELETE tombstones, is retained with an immutable digest.
CREATE TABLE IF NOT EXISTS attendance_teacher_event_history (
  event_id BIGINT PRIMARY KEY CHECK(event_id>0),
  teaching_schedule_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attendance_history_schedule
  ON attendance_teacher_event_history(teaching_schedule_id,event_id);

CREATE OR REPLACE FUNCTION iig_attendance_block_history_mutations()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Attendance historical events are immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_attendance_immutable_history
  ON attendance_teacher_event_history;
CREATE TRIGGER trg_iig_attendance_immutable_history
  BEFORE UPDATE OR DELETE ON attendance_teacher_event_history
  FOR EACH ROW EXECUTE FUNCTION iig_attendance_block_history_mutations();

-- A separate HISTORY cursor is mandatory: MAX(event_id) of live deliveries
-- can skip old events when live relay commits newer events first.
CREATE TABLE IF NOT EXISTS attendance_history_backfill_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO attendance_history_backfill_cursor(singleton,last_event_id)
VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING;

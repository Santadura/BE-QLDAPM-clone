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

-- Spring's schema.sql initializer separates statements at semicolons,
-- including those inside PostgreSQL PL/pgSQL dollar quotes. The complete
-- function and trigger are installed from one JDBC statement each by
-- AttendanceHistoryImmutability on application startup (fail-closed).

-- A separate HISTORY cursor is mandatory: MAX(event_id) of live deliveries
-- can skip old events when live relay commits newer events first.
CREATE TABLE IF NOT EXISTS attendance_history_backfill_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO attendance_history_backfill_cursor(singleton,last_event_id)
VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING;


-- New native Attendance write model. Not the legacy Core source mirror.
-- No HTTP controller may invoke it before Core's audited exclusive
-- ATTENDANCE ownership admission protocol has been implemented and proven.
-- Commands, typed presence and immutable outbox commit in ONE Attendance PG
-- transaction. A remote Core reference receipt cannot be inferred from a
-- 200/timeout: confirmation is a separate durable state transition.
CREATE TABLE IF NOT EXISTS attendance_native_teacher_rows (
  teaching_schedule_id VARCHAR(50) PRIMARY KEY,
  -- Core owns the teacher/class roster; the immutable admission proves
  -- which class this globally unique schedule belongs to.
  attendance_id VARCHAR(50) NOT NULL,
  status VARCHAR(20) CHECK(status IN ('PRESENT','ABSENT','LATE')),
  recorded_by VARCHAR(50),
  version BIGINT NOT NULL CHECK(version>=0),
  recorded_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT attendance_native_initial_state CHECK(
    (status IS NULL AND recorded_by IS NULL AND recorded_at IS NULL) OR
    (status IS NOT NULL AND recorded_by IS NOT NULL AND recorded_at IS NOT NULL))
);
ALTER TABLE attendance_native_teacher_rows
  DROP COLUMN IF EXISTS class_id;
CREATE INDEX IF NOT EXISTS idx_attendance_native_recorded_by
  ON attendance_native_teacher_rows(recorded_by,updated_at);

CREATE TABLE IF NOT EXISTS attendance_native_commands (
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
  facts_token CHAR(64) NOT NULL CHECK(facts_token ~ '^[0-9a-f]{64}$'),
  writer_generation BIGINT NOT NULL CHECK(writer_generation>0),
  event_id UUID NOT NULL UNIQUE,
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING_REFERENCE'
    CHECK(state IN ('PENDING_REFERENCE','COMMITTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,class_id,teaching_schedule_id,request_key),
  CONSTRAINT native_attendance_completion CHECK (
    (state='PENDING_REFERENCE' AND completed_at IS NULL) OR
    (state='COMMITTED' AND completed_at IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_attendance_native_pending
  ON attendance_native_commands(created_at)
  WHERE state='PENDING_REFERENCE';

CREATE TABLE IF NOT EXISTS attendance_native_outbox (
  event_id UUID PRIMARY KEY REFERENCES attendance_native_commands(event_id),
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL CHECK(version>0),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  payload_canonical TEXT NOT NULL,
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  confirmed_at TIMESTAMPTZ,
  reference_receipt JSONB,
  CONSTRAINT native_outbox_reference_completion CHECK(
    (confirmed_at IS NULL AND reference_receipt IS NULL) OR
    (confirmed_at IS NOT NULL AND jsonb_typeof(reference_receipt)='object')
  ),
  FOREIGN KEY(actor_username,class_id,teaching_schedule_id,request_key)
    REFERENCES attendance_native_commands
      (actor_username,class_id,teaching_schedule_id,request_key),
  UNIQUE(teaching_schedule_id,version),
  UNIQUE(actor_username,class_id,teaching_schedule_id,request_key)
);
CREATE INDEX IF NOT EXISTS idx_attendance_native_outbox_pending
  ON attendance_native_outbox(created_at,event_id)
  WHERE confirmed_at IS NULL;


-- Immutable one-way activation receipt. The next Core writer generation
-- becomes authoritative only after full frozen Core+Academic history proof.
-- The native command ledger never accepts a generation without this receipt.
CREATE TABLE IF NOT EXISTS attendance_native_activations (
  writer_generation BIGINT PRIMARY KEY CHECK(writer_generation>1),
  source_rows BIGINT NOT NULL CHECK(source_rows>=0),
  historical_events BIGINT NOT NULL CHECK(historical_events>=0),
  source_last_event_id BIGINT NOT NULL CHECK(source_last_event_id>=0),
  academic_proof_hash CHAR(64) NOT NULL
    CHECK(academic_proof_hash ~ '^[0-9a-f]{64}$'),
  activated_by VARCHAR(100) NOT NULL,
  imported_live_rows BIGINT NOT NULL CHECK(imported_live_rows>=0),
  activated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Durable finality supervision for native Attendance. Quarantine does NOT
-- cancel or roll back an event; only the exact Core reference can complete it.
-- This table is intentionally separate from the immutable domain outbox.
CREATE TABLE IF NOT EXISTS attendance_native_recovery_attempts (
  event_id UUID PRIMARY KEY REFERENCES attendance_native_outbox(event_id)
    ON DELETE RESTRICT,
  failure_count INTEGER NOT NULL CHECK(failure_count BETWEEN 1 AND 1000000),
  state VARCHAR(20) NOT NULL CHECK(state IN ('RETRY','OPERATOR_REVIEW','RESOLVED')),
  next_attempt_at TIMESTAMPTZ NOT NULL,
  last_failure_class VARCHAR(120) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS attendance_native_recovery_operator_queue
  ON attendance_native_recovery_attempts(state,next_attempt_at)
  WHERE state IN ('RETRY','OPERATOR_REVIEW');

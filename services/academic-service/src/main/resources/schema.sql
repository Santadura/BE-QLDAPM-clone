-- Academic owns its own PostgreSQL read snapshots. Core remains the
-- authoritative source of class/session writes during this bridge stage.
-- Snapshots are partitioned by validated actor+role+resource; no cached data
-- is ever served when Core authentication or the live source is unavailable.
CREATE TABLE IF NOT EXISTS academic_read_snapshots (
  snapshot_key CHAR(64) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  role_id VARCHAR(30) NOT NULL,
  resource_kind VARCHAR(30) NOT NULL CHECK (resource_kind IN ('CLASS_PLANNING','PLANNED_SESSIONS')),
  payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS academic_read_snapshots_actor
  ON academic_read_snapshots (username,role_id,synchronized_at DESC);

-- This is the actual Academic-owned class-planning read source. Core holds
-- the monotonic revision clock; Academic keeps a validated local snapshot,
-- refreshing only when Core has transactionally advanced that revision.
CREATE TABLE IF NOT EXISTS academic_planning_projection (
  name VARCHAR(20) PRIMARY KEY CHECK(name='planning'),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Shared per-class Academic read projection: never returned unless a
-- separate live Core class-level authorization and version check succeeds.
-- A global Core session mutation revision makes changes in ANY class
-- invalidate the cached candidate; revisions only move forward.
CREATE TABLE IF NOT EXISTS academic_session_projections (
  class_id VARCHAR(50) PRIMARY KEY,
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);


-- Transitional Academic HTTP command ingress; Core remains the ONLY writer.
-- Local receipts survive uncertain Core responses and never authorize replay.
CREATE TABLE IF NOT EXISTS academic_session_command_inbox (
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  operation VARCHAR(20) NOT NULL CHECK(operation IN ('PLAN','RESCHEDULE')),
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','COMMITTED')),
  response_payload JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,class_id,operation,request_key),
  CONSTRAINT academic_command_receipt_complete CHECK (
    (state='PENDING' AND response_payload IS NULL AND completed_at IS NULL) OR
    (state='COMMITTED' AND jsonb_typeof(response_payload)='array' AND completed_at IS NOT NULL)
  )
);


-- Full immutable source-event receipts, plus an independently persisted latest
-- Academic class-session state. Deletion is a tombstone, never a silent erase.
CREATE TABLE IF NOT EXISTS academic_session_source_receipts (
  event_id BIGINT PRIMARY KEY CHECK(event_id>0),
  class_session_id VARCHAR(50) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload_hash CHAR(64) NOT NULL,
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS academic_session_receipts_by_session
  ON academic_session_source_receipts(class_session_id,event_id DESC);

CREATE TABLE IF NOT EXISTS academic_session_owned_projection (
  class_session_id VARCHAR(50) PRIMARY KEY,
  class_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS academic_owned_sessions_by_class
  ON academic_session_owned_projection(class_id,event_kind,source_event_id);

CREATE TABLE IF NOT EXISTS academic_session_history_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO academic_session_history_cursor(singleton,last_event_id)
  VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING;


-- Receipt evidence may not be re-written or silently deleted, even by a
-- direct SQL maintenance operation. A duplicate ID with different JSON must
-- be caught by the importer, not repaired by changing immutable provenance.
CREATE OR REPLACE FUNCTION iig_guard_academic_source_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic source receipts are immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_academic_receipt_mutation
  ON academic_session_source_receipts;
CREATE TRIGGER trg_iig_guard_academic_receipt_mutation
  BEFORE UPDATE OR DELETE ON academic_session_source_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_source_receipt();
DROP TRIGGER IF EXISTS trg_iig_guard_academic_receipt_truncate
  ON academic_session_source_receipts;
CREATE TRIGGER trg_iig_guard_academic_receipt_truncate
  BEFORE TRUNCATE ON academic_session_source_receipts
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_academic_source_receipt();

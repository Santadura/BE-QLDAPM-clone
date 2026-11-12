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
)@@
CREATE INDEX IF NOT EXISTS academic_read_snapshots_actor
  ON academic_read_snapshots (username,role_id,synchronized_at DESC)@@

-- This is the actual Academic-owned class-planning read source. Core holds
-- the monotonic revision clock; Academic keeps a validated local snapshot,
-- refreshing only when Core has transactionally advanced that revision.
CREATE TABLE IF NOT EXISTS academic_planning_projection (
  name VARCHAR(20) PRIMARY KEY CHECK(name='planning'),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@

-- Shared per-class Academic read projection: never returned unless a
-- separate live Core class-level authorization and version check succeeds.
-- A global Core session mutation revision makes changes in ANY class
-- invalidate the cached candidate; revisions only move forward.
CREATE TABLE IF NOT EXISTS academic_session_projections (
  class_id VARCHAR(50) PRIMARY KEY,
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@


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
)@@


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
)@@
CREATE INDEX IF NOT EXISTS academic_session_receipts_by_session
  ON academic_session_source_receipts(class_session_id,event_id DESC)@@

CREATE TABLE IF NOT EXISTS academic_session_owned_projection (
  class_session_id VARCHAR(50) PRIMARY KEY,
  class_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
CREATE INDEX IF NOT EXISTS academic_owned_sessions_by_class
  ON academic_session_owned_projection(class_id,event_kind,source_event_id)@@

CREATE TABLE IF NOT EXISTS academic_session_history_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
INSERT INTO academic_session_history_cursor(singleton,last_event_id)
  VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING@@


-- Receipt evidence may not be re-written or silently deleted, even by a
-- direct SQL maintenance operation. A duplicate ID with different JSON must
-- be caught by the importer, not repaired by changing immutable provenance.
CREATE OR REPLACE FUNCTION iig_guard_academic_source_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic source receipts are immutable'
    USING ERRCODE='55000';
END;
$$@@
DROP TRIGGER IF EXISTS trg_iig_guard_academic_receipt_mutation
  ON academic_session_source_receipts@@
CREATE TRIGGER trg_iig_guard_academic_receipt_mutation
  BEFORE UPDATE OR DELETE ON academic_session_source_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_source_receipt()@@
DROP TRIGGER IF EXISTS trg_iig_guard_academic_receipt_truncate
  ON academic_session_source_receipts@@
CREATE TRIGGER trg_iig_guard_academic_receipt_truncate
  BEFORE TRUNCATE ON academic_session_source_receipts
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_academic_source_receipt()@@


-- Typed, independently persisted Academic timetable shadow. It is not yet
-- a writable source of truth; the original Core class/session transaction
-- and cross-service student/teacher/CS guards remain authoritative.
-- Tombstones are retained with their original source values.
CREATE TABLE IF NOT EXISTS academic_class_sessions_shadow (
  class_session_id VARCHAR(50) PRIMARY KEY,
  class_id VARCHAR(50) NOT NULL,
  session_date DATE NOT NULL,
  start_time TIME(0) NOT NULL,
  end_time TIME(0) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK(status IN ('PLANNED','CANCELLED')),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT academic_shadow_time_valid CHECK(end_time>start_time)
)@@
CREATE INDEX IF NOT EXISTS academic_shadow_by_class_slot
  ON academic_class_sessions_shadow(class_id,session_date,start_time)@@

-- Safe upgrade of an already-running Academic read model. Import all
-- pre-existing latest JSON rows, not only new events after startup.
-- Never regress a more recent source event in the shadow.
INSERT INTO academic_class_sessions_shadow
  (class_session_id,class_id,session_date,start_time,end_time,status,
   event_kind,source_event_id,source_revision)
SELECT class_session_id,class_id,
       (source_payload->>'date')::date,
       (source_payload->>'start_time')::time,
       (source_payload->>'end_time')::time,
       source_payload->>'status',event_kind,source_event_id,source_revision
FROM academic_session_owned_projection
WHERE TRUE -- disambiguate INSERT .. SELECT .. ON CONFLICT for PostgreSQL
ON CONFLICT(class_session_id) DO UPDATE SET
  class_id=EXCLUDED.class_id,
  session_date=EXCLUDED.session_date,
  start_time=EXCLUDED.start_time,
  end_time=EXCLUDED.end_time,
  status=EXCLUDED.status,
  event_kind=EXCLUDED.event_kind,
  source_event_id=EXCLUDED.source_event_id,
  source_revision=EXCLUDED.source_revision,
  updated_at=NOW()
WHERE academic_class_sessions_shadow.source_event_id<EXCLUDED.source_event_id@@


-- Phase 8n: locally owned, ATOMIC but NON-AUTHORITATIVE Academic command
-- rehearsal. A candidate never modifies the Core-source projection and the
-- outbox event is deliberately not delivered to Core/Scheduling/Attendance.
-- An approved domain-writer cutover must use separate ownership migrations.
CREATE TABLE IF NOT EXISTS academic_session_candidate_commands (
  operator_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  command_id UUID NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  class_session_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  academic_generation BIGINT NOT NULL CHECK(academic_generation>0),
  attendance_generation BIGINT NOT NULL CHECK(attendance_generation>0),
  scheduling_generation BIGINT NOT NULL CHECK(scheduling_generation>0),
  action VARCHAR(15) NOT NULL CHECK(action IN ('RESCHEDULE','CANCEL')),
  state VARCHAR(12) NOT NULL DEFAULT 'STAGED' CHECK(state='STAGED'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(operator_username,request_key)
)@@
CREATE INDEX IF NOT EXISTS academic_candidate_by_session
  ON academic_session_candidate_commands(class_session_id,created_at DESC)@@

CREATE TABLE IF NOT EXISTS academic_session_candidate_slots (
  command_id UUID PRIMARY KEY REFERENCES academic_session_candidate_commands(command_id)
    ON DELETE RESTRICT,
  class_id VARCHAR(50) NOT NULL,
  class_session_id VARCHAR(50) NOT NULL,
  session_date DATE NOT NULL,
  start_time TIME(0) NOT NULL,
  end_time TIME(0) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK(status IN ('PLANNED','CANCELLED')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT academic_candidate_slot_time_valid CHECK(end_time>start_time)
)@@

CREATE TABLE IF NOT EXISTS academic_session_candidate_outbox (
  outbox_id UUID PRIMARY KEY,
  command_id UUID NOT NULL UNIQUE REFERENCES academic_session_candidate_commands(command_id)
    ON DELETE RESTRICT,
  event_kind VARCHAR(30) NOT NULL DEFAULT 'REHEARSAL_ONLY'
    CHECK(event_kind='REHEARSAL_ONLY'),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@

-- Database-level guarantees: no direct SQL may silently rewrite a
-- committed candidate or forge a delivered domain mutation after staging.
CREATE OR REPLACE FUNCTION iig_block_academic_candidate_tampering()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic candidate commands, slots and outbox are immutable'
    USING ERRCODE='55000';
END;
$$@@
DROP TRIGGER IF EXISTS trg_iig_candidate_command_immutable
  ON academic_session_candidate_commands@@
CREATE TRIGGER trg_iig_candidate_command_immutable
  BEFORE UPDATE OR DELETE ON academic_session_candidate_commands
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_command_no_truncate
  ON academic_session_candidate_commands@@
CREATE TRIGGER trg_iig_candidate_command_no_truncate
  BEFORE TRUNCATE ON academic_session_candidate_commands
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_slot_immutable
  ON academic_session_candidate_slots@@
CREATE TRIGGER trg_iig_candidate_slot_immutable
  BEFORE UPDATE OR DELETE ON academic_session_candidate_slots
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_slot_no_truncate
  ON academic_session_candidate_slots@@
CREATE TRIGGER trg_iig_candidate_slot_no_truncate
  BEFORE TRUNCATE ON academic_session_candidate_slots
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_outbox_immutable
  ON academic_session_candidate_outbox@@
CREATE TRIGGER trg_iig_candidate_outbox_immutable
  BEFORE UPDATE OR DELETE ON academic_session_candidate_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_outbox_no_truncate
  ON academic_session_candidate_outbox@@
CREATE TRIGGER trg_iig_candidate_outbox_no_truncate
  BEFORE TRUNCATE ON academic_session_candidate_outbox
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@


-- Phase 8q: candidate Core handback receipts are the local "remote ack"
-- counterpart of Core's immutable acquisition/release audit. This is NOT
-- a candidate domain event dispatcher and has no live timetable effects.
CREATE TABLE IF NOT EXISTS academic_candidate_handoff_receipts (
  command_id UUID PRIMARY KEY REFERENCES academic_session_candidate_commands(command_id),
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  core_outbox_hash CHAR(64) NOT NULL CHECK(core_outbox_hash ~ '^[0-9a-f]{64}$'),
  released_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(actor_username,request_key)
)@@
DROP TRIGGER IF EXISTS trg_iig_candidate_handoff_receipt_immutable
  ON academic_candidate_handoff_receipts@@
CREATE TRIGGER trg_iig_candidate_handoff_receipt_immutable
  BEFORE UPDATE OR DELETE ON academic_candidate_handoff_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_candidate_handoff_receipt_no_truncate
  ON academic_candidate_handoff_receipts@@
CREATE TRIGGER trg_iig_candidate_handoff_receipt_no_truncate
  BEFORE TRUNCATE ON academic_candidate_handoff_receipts
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@


-- Phase 8r: independently persisted Academic student + class roster source.
-- Core remains public write owner. This schema never writes Core's database.
-- Every source event remains immutable, including DELETE tombstones.
CREATE TABLE IF NOT EXISTS academic_roster_source_receipts (
  event_id BIGINT PRIMARY KEY CHECK(event_id>0),
  entity_type VARCHAR(20) NOT NULL CHECK(entity_type IN ('STUDENT','MEMBERSHIP')),
  entity_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  imported_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
CREATE INDEX IF NOT EXISTS academic_roster_receipts_entity
  ON academic_roster_source_receipts(entity_type,entity_id,event_id DESC)@@

CREATE TABLE IF NOT EXISTS academic_students_shadow (
  student_id VARCHAR(50) PRIMARY KEY,
  student_code VARCHAR(30) NOT NULL,
  full_name VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@

CREATE TABLE IF NOT EXISTS academic_class_memberships_shadow (
  class_student_id VARCHAR(50) PRIMARY KEY,
  class_id VARCHAR(50) NOT NULL,
  student_id VARCHAR(50) NOT NULL,
  membership_status VARCHAR(20) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
CREATE INDEX IF NOT EXISTS academic_roster_membership_by_class
  ON academic_class_memberships_shadow(class_id,student_id,event_kind)@@

CREATE TABLE IF NOT EXISTS academic_roster_history_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
INSERT INTO academic_roster_history_cursor(singleton,last_event_id)
  VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING@@

CREATE OR REPLACE FUNCTION iig_block_roster_receipt_tampering()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic roster source receipts are immutable'
    USING ERRCODE='55000';
END;
$$@@
DROP TRIGGER IF EXISTS trg_iig_roster_receipts_immutable
  ON academic_roster_source_receipts@@
CREATE TRIGGER trg_iig_roster_receipts_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_source_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_block_roster_receipt_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_receipts_no_truncate
  ON academic_roster_source_receipts@@
CREATE TRIGGER trg_iig_roster_receipts_no_truncate
  BEFORE TRUNCATE ON academic_roster_source_receipts
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_roster_receipt_tampering()@@

-- Phase 8s: own typed read-only historical targets and results. Existing
-- Student/Membership receipts remain unchanged and are NEVER rewritten.
ALTER TABLE academic_roster_source_receipts
  DROP CONSTRAINT IF EXISTS academic_roster_source_receipts_entity_type_check@@
ALTER TABLE academic_roster_source_receipts
  ADD CONSTRAINT academic_roster_source_receipts_entity_type_check
  CHECK(entity_type IN ('STUDENT','MEMBERSHIP','TARGET','RESULT'))@@

CREATE TABLE IF NOT EXISTS academic_student_targets_shadow (
  student_target_id VARCHAR(50) PRIMARY KEY,
  student_id VARCHAR(50) NOT NULL,
  course_id VARCHAR(50) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_value NUMERIC(8,2) NOT NULL CHECK(target_value>=0),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
CREATE INDEX IF NOT EXISTS academic_target_by_student
  ON academic_student_targets_shadow(student_id,event_kind)@@

CREATE TABLE IF NOT EXISTS academic_student_results_shadow (
  student_result_id VARCHAR(50) PRIMARY KEY,
  student_id VARCHAR(50) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  score NUMERIC(5,2) NOT NULL CHECK(score>=0),
  evaluated_by VARCHAR(50) NOT NULL,
  evaluated_at TIMESTAMPTZ NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  source_payload JSONB NOT NULL CHECK(jsonb_typeof(source_payload)='object'),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@
CREATE INDEX IF NOT EXISTS academic_result_by_student
  ON academic_student_results_shadow(student_id,event_kind,evaluated_at DESC)@@

-- Phase 8u: independent, durable but NON-AUTHORITATIVE Academic Target/Result
-- candidate commands. The three facts (command, proposed JSON, outbox)
-- commit atomically in Academic PostgreSQL. NO dispatcher consumes this
-- REHEARSAL_ONLY outbox, and no public Core/Academic source rows are changed.
CREATE TABLE IF NOT EXISTS academic_roster_candidate_commands (
  command_id UUID NOT NULL UNIQUE,
  operator_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
  entity_type VARCHAR(20) NOT NULL CHECK(entity_type IN ('TARGET','RESULT')),
  entity_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  writer_generation BIGINT NOT NULL CHECK(writer_generation>=2),
  action VARCHAR(10) NOT NULL CHECK(action IN ('UPSERT','DELETE')),
  state VARCHAR(12) NOT NULL DEFAULT 'STAGED' CHECK(state='STAGED'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(operator_username,request_key),
  UNIQUE(entity_type,entity_id,source_event_id)
)@@

CREATE TABLE IF NOT EXISTS academic_roster_candidate_payloads (
  command_id UUID PRIMARY KEY REFERENCES academic_roster_candidate_commands(command_id)
    ON DELETE RESTRICT,
  proposed_payload JSONB NOT NULL CHECK(jsonb_typeof(proposed_payload)='object'),
  proposed_value NUMERIC(8,2) CHECK(proposed_value>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@

CREATE TABLE IF NOT EXISTS academic_roster_candidate_outbox (
  outbox_id UUID PRIMARY KEY,
  command_id UUID NOT NULL UNIQUE REFERENCES academic_roster_candidate_commands(command_id)
    ON DELETE RESTRICT,
  event_kind VARCHAR(30) NOT NULL DEFAULT 'REHEARSAL_ONLY'
    CHECK(event_kind='REHEARSAL_ONLY'),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
)@@

DROP TRIGGER IF EXISTS trg_iig_roster_candidate_command_immutable
  ON academic_roster_candidate_commands@@
CREATE TRIGGER trg_iig_roster_candidate_command_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_candidate_commands
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_candidate_command_no_truncate
  ON academic_roster_candidate_commands@@
CREATE TRIGGER trg_iig_roster_candidate_command_no_truncate
  BEFORE TRUNCATE ON academic_roster_candidate_commands
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_candidate_payload_immutable
  ON academic_roster_candidate_payloads@@
CREATE TRIGGER trg_iig_roster_candidate_payload_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_candidate_payloads
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_candidate_payload_no_truncate
  ON academic_roster_candidate_payloads@@
CREATE TRIGGER trg_iig_roster_candidate_payload_no_truncate
  BEFORE TRUNCATE ON academic_roster_candidate_payloads
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_candidate_outbox_immutable
  ON academic_roster_candidate_outbox@@
CREATE TRIGGER trg_iig_roster_candidate_outbox_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_candidate_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@
DROP TRIGGER IF EXISTS trg_iig_roster_candidate_outbox_no_truncate
  ON academic_roster_candidate_outbox@@
CREATE TRIGGER trg_iig_roster_candidate_outbox_no_truncate
  BEFORE TRUNCATE ON academic_roster_candidate_outbox
  FOR EACH STATEMENT EXECUTE FUNCTION iig_block_academic_candidate_tampering()@@

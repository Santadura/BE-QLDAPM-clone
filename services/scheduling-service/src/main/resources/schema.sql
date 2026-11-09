-- This service OWNS availability registration and approval. The Core table
-- availabilities is an eventually synchronized FK/reference projection only.
CREATE TABLE IF NOT EXISTS scheduling_availabilities (
  availability_id VARCHAR(50) PRIMARY KEY,
  employee_id VARCHAR(50) NOT NULL,
  full_name VARCHAR(150) NOT NULL,
  role_id VARCHAR(30) NOT NULL CHECK (role_id IN ('TEACHER','CS','SALE')),
  available_date DATE,
  day_of_week VARCHAR(10) NOT NULL,
  shift VARCHAR(50),
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  availability_type VARCHAR(30) NOT NULL,
  campus VARCHAR(100),
  note VARCHAR(255),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at TIMESTAMPTZ,
  reviewed_by VARCHAR(50),
  version BIGINT NOT NULL DEFAULT 1 CHECK (version>=1),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK (end_time>start_time),
  CHECK ((availability_type='WEEKLY' AND available_date IS NOT NULL) OR
         (availability_type='RECURRING' AND available_date IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_scheduling_availabilities_worker
  ON scheduling_availabilities(employee_id,available_date,day_of_week)
  WHERE deleted=FALSE;
CREATE TABLE IF NOT EXISTS scheduling_availability_outbox (
  availability_id VARCHAR(50) PRIMARY KEY REFERENCES scheduling_availabilities(availability_id),
  version BIGINT NOT NULL CHECK (version>=1),
  delivered_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS scheduling_availability_reservations (
  reservation_id VARCHAR(50) PRIMARY KEY,
  availability_id VARCHAR(50) NOT NULL REFERENCES scheduling_availabilities(availability_id),
  employee_id VARCHAR(50) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_scheduling_reservations_active
  ON scheduling_availability_reservations(availability_id,expires_at);
-- Phase 8b: Scheduling also owns short-lived employee/time assignment
-- intents. A reservation uses the same ID and expiration as its approved
-- availability lease, so commit/rollback release and expiry clean both.
-- PostgreSQL employee advisory locks serialize overlap checks with inserts.
CREATE TABLE IF NOT EXISTS scheduling_assignment_intent_reservations (
  reservation_id VARCHAR(50) PRIMARY KEY
    REFERENCES scheduling_availability_reservations(reservation_id) ON DELETE CASCADE,
  employee_id VARCHAR(50) NOT NULL,
  work_date DATE NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  CHECK (end_time > start_time)
);
CREATE INDEX IF NOT EXISTS idx_scheduling_intent_overlap
  ON scheduling_assignment_intent_reservations(employee_id,work_date,start_time,end_time);

CREATE TABLE IF NOT EXISTS scheduling_metadata (
  key VARCHAR(80) PRIMARY KEY,
  value VARCHAR(200) NOT NULL
);

-- Distinct append-only audit stream: a collapsed latest-version projection
-- must never erase intermediate REGISTER/REVIEW/UPDATE/DELETE audit events.
CREATE TABLE IF NOT EXISTS scheduling_availability_audit_outbox (
  event_id VARCHAR(50) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  action VARCHAR(50) NOT NULL,
  availability_id VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_scheduling_audit_pending
  ON scheduling_availability_audit_outbox(created_at,event_id)
  WHERE delivered_at IS NULL;

-- Assignment READ model only. Core is still the sole assignment writer.
-- Keep tombstones so a delayed/crossed delivery cannot resurrect a deletion.
CREATE TABLE IF NOT EXISTS scheduling_assignment_read_model (
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('TEACHING','STAFF')),
  schedule_id VARCHAR(50) NOT NULL,
  version BIGINT NOT NULL CHECK (version>=1),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  employee_id VARCHAR(50),
  staff_role VARCHAR(50),
  work_date DATE,
  status VARCHAR(20),
  payload JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (source_type,schedule_id)
);
CREATE INDEX IF NOT EXISTS idx_scheduling_assignment_read_week
  ON scheduling_assignment_read_model (source_type,work_date,employee_id)
  WHERE deleted=FALSE;

-- Durable independent Scheduling command inbox. The only enabled state
-- in this phase is STAGED; no worker is allowed to commit it as an assignment
-- until Academic contracts and compensation are ready. It provides a stable
-- actor-scoped command identity and exact payload digest for future retries.
CREATE TABLE IF NOT EXISTS scheduling_assignment_command_inbox (
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(40) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'STAGED'
    CHECK (state IN ('STAGED','PENDING_REFERENCE','COMMITTED','REJECTED','COMPENSATING')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(actor_username,operation,request_key)
);
CREATE INDEX IF NOT EXISTS idx_scheduling_command_inbox_state
  ON scheduling_assignment_command_inbox(state,created_at);
-- Existing persistent Docker volumes may have the narrower pre-outbox CHECK.
-- Apply the state-machine expansion idempotently without deleting commands.
ALTER TABLE scheduling_assignment_command_inbox
  DROP CONSTRAINT IF EXISTS scheduling_assignment_command_inbox_state_check;
ALTER TABLE scheduling_assignment_command_inbox
  ADD CONSTRAINT scheduling_assignment_command_inbox_state_check
  CHECK (state IN ('STAGED','PENDING_REFERENCE','COMMITTED','REJECTED','COMPENSATING'));

-- Phase 8d: durable replayable outbound change stream. The feature-gated
-- private standalone CS/Sale executor may produce events while Core is
-- QUIESCED; public and classroom writes remain owned by Core. Mutation, event
-- and inbox state must commit in the SAME Scheduling transaction.
CREATE TABLE IF NOT EXISTS scheduling_assignment_domain_outbox (
  event_id VARCHAR(50) PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(40) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('TEACHING','STAFF')),
  schedule_id VARCHAR(50) NOT NULL,
  revision BIGINT NOT NULL CHECK (revision>=1),
  event_kind VARCHAR(12) NOT NULL CHECK (event_kind IN ('UPSERT','TOMBSTONE')),
  payload JSONB NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delivered_at TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
  last_error VARCHAR(300),
  FOREIGN KEY(actor_username,operation,request_key)
    REFERENCES scheduling_assignment_command_inbox(actor_username,operation,request_key),
  UNIQUE(source_type,schedule_id,revision)
);
CREATE INDEX IF NOT EXISTS idx_scheduling_assignment_outbound_pending
  ON scheduling_assignment_domain_outbox(next_attempt_at,created_at,event_id)
  WHERE delivered_at IS NULL;

-- Each Scheduling outbox event is a durable immutable business fact.
-- Retry/delivery metadata may change, but payload, digest, revision and
-- ownership can never be rewritten or deleted after local commit.
CREATE OR REPLACE FUNCTION iig_guard_assignment_event_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Committed assignment events cannot be deleted'
      USING ERRCODE='55000';
  END IF;
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
     OR NEW.actor_username IS DISTINCT FROM OLD.actor_username
     OR NEW.operation IS DISTINCT FROM OLD.operation
     OR NEW.request_key IS DISTINCT FROM OLD.request_key
     OR NEW.source_type IS DISTINCT FROM OLD.source_type
     OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
     OR NEW.revision IS DISTINCT FROM OLD.revision
     OR NEW.event_kind IS DISTINCT FROM OLD.event_kind
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
     OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR (OLD.delivered_at IS NOT NULL
         AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at)
  THEN
    RAISE EXCEPTION 'Immutable Scheduling assignment event was modified'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$;
DROP TRIGGER IF EXISTS trg_iig_assignment_event_immutable
  ON scheduling_assignment_domain_outbox;
CREATE TRIGGER trg_iig_assignment_event_immutable
  BEFORE UPDATE OR DELETE ON scheduling_assignment_domain_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_guard_assignment_event_immutable();

-- Enforce command-inbox state transitions at the Scheduling DB layer.
-- HTTP retries and controller bugs must not be able to claim COMMITTED
-- without actual immutable, acknowledged domain events. A rejected staged
-- request cannot suppress events that have already been committed.
CREATE OR REPLACE FUNCTION iig_guard_assignment_command_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE total BIGINT; pending BIGINT;
BEGIN
  IF NEW.state=OLD.state THEN RETURN NEW; END IF;
  IF OLD.state IN ('REJECTED','COMMITTED') THEN
    RAISE EXCEPTION 'Terminal assignment command state is immutable'
      USING ERRCODE='55000';
  END IF;
  SELECT count(*),count(*) FILTER (WHERE delivered_at IS NULL)
    INTO total,pending FROM scheduling_assignment_domain_outbox
    WHERE actor_username=NEW.actor_username
      AND operation=NEW.operation AND request_key=NEW.request_key;
  IF NEW.state='REJECTED' AND (OLD.state<>'STAGED' OR total>0) THEN
    RAISE EXCEPTION 'Cannot reject command with committed domain events'
      USING ERRCODE='55000';
  END IF;
  IF NEW.state='PENDING_REFERENCE'
     AND (OLD.state<>'STAGED' OR total=0) THEN
    RAISE EXCEPTION 'Reference pending requires atomic domain events'
      USING ERRCODE='55000';
  END IF;
  IF NEW.state='COMMITTED' AND (total=0 OR pending>0 OR
       OLD.state NOT IN ('PENDING_REFERENCE','COMPENSATING')) THEN
    RAISE EXCEPTION 'Assignment command cannot finish with undelivered events'
      USING ERRCODE='55000';
  END IF;
  IF NEW.state='STAGED' OR
     (NEW.state='COMPENSATING' AND OLD.state<>'PENDING_REFERENCE') THEN
    RAISE EXCEPTION 'Invalid assignment command state regression'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_assignment_command_state ON scheduling_assignment_command_inbox;
CREATE TRIGGER trg_iig_assignment_command_state
  BEFORE UPDATE OF state ON scheduling_assignment_command_inbox
  FOR EACH ROW EXECUTE FUNCTION iig_guard_assignment_command_state();

-- Phase 8b-3b candidate assignment WRITE model. During migration, Core's
-- transactional outbox is still the ONLY source of writes. The independent
-- normalized store is transactionally maintained alongside the FE read copy.
-- No public mutation route uses it until Academic, attendance and compensation
-- are independently valid. Deletion tombstones stay to fence delayed retries.
CREATE TABLE IF NOT EXISTS scheduling_assignment_write_shadow (
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('TEACHING','STAFF')),
  schedule_id VARCHAR(50) NOT NULL,
  version BIGINT NOT NULL CHECK (version>=1),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  employee_id VARCHAR(50),
  staff_role VARCHAR(50),
  class_id VARCHAR(50),
  workplace_id VARCHAR(50),
  work_date DATE,
  start_time TIME,
  end_time TIME,
  work_type VARCHAR(50),
  status VARCHAR(20),
  assigned_by VARCHAR(50),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(source_type,schedule_id),
  CONSTRAINT scheduling_shadow_live_fields CHECK (
    deleted OR (employee_id IS NOT NULL AND work_date IS NOT NULL
      AND start_time IS NOT NULL AND end_time IS NOT NULL
      AND end_time>start_time))
);
CREATE INDEX IF NOT EXISTS idx_scheduling_shadow_person_overlap
  ON scheduling_assignment_write_shadow(employee_id,work_date,start_time,end_time)
  WHERE deleted=FALSE AND status='ASSIGNED';
CREATE INDEX IF NOT EXISTS idx_scheduling_shadow_class_coverage
  ON scheduling_assignment_write_shadow(class_id,work_date,source_type,status)
  WHERE deleted=FALSE;

-- First-deploy backfill for nonempty databases that imported Scheduling read
-- projections before the normalized shadow store was introduced. Repeated
-- application never downgrades a newer shadow version. An incomplete backfill
-- is diagnosed by the strict cutover readiness gate.
INSERT INTO scheduling_assignment_write_shadow (
  source_type,schedule_id,version,deleted,employee_id,staff_role,class_id,
  workplace_id,work_date,start_time,end_time,work_type,status,assigned_by
)
SELECT source_type,schedule_id,version,deleted,employee_id,staff_role,
  payload->>'classId',payload->>'workplaceId',work_date,
  NULLIF(payload->>'startTime','')::TIME,
  NULLIF(payload->>'endTime','')::TIME,
  payload->>'workType',status,payload->>'assignedBy'
FROM scheduling_assignment_read_model WHERE TRUE
ON CONFLICT(source_type,schedule_id) DO UPDATE SET
  version=EXCLUDED.version,deleted=EXCLUDED.deleted,
  employee_id=EXCLUDED.employee_id,staff_role=EXCLUDED.staff_role,
  class_id=EXCLUDED.class_id,workplace_id=EXCLUDED.workplace_id,
  work_date=EXCLUDED.work_date,start_time=EXCLUDED.start_time,
  end_time=EXCLUDED.end_time,work_type=EXCLUDED.work_type,
  status=EXCLUDED.status,assigned_by=EXCLUDED.assigned_by,updated_at=NOW()
WHERE scheduling_assignment_write_shadow.version<EXCLUDED.version;

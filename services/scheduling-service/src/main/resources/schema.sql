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

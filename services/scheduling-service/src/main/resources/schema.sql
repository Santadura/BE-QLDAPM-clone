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
CREATE TABLE IF NOT EXISTS scheduling_metadata (
  key VARCHAR(80) PRIMARY KEY,
  value VARCHAR(200) NOT NULL
);

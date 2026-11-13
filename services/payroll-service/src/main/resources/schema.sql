-- Payroll now owns the only public calculation and payroll ledger.
-- Legacy Core salary tables are copied ONCE and remain available for rollback.
-- Payroll has no JDBC connection or foreign keys to HR/Scheduling/Core.
CREATE TABLE IF NOT EXISTS payroll_ledgers (
  id VARCHAR(50) PRIMARY KEY,
  employee_id VARCHAR(50) NOT NULL,
  employee_name VARCHAR(100) NOT NULL,
  employee_code VARCHAR(30),
  role_id VARCHAR(50) NOT NULL,
  period VARCHAR(7) NOT NULL,
  source VARCHAR(20) NOT NULL CHECK (source IN ('EMPLOYEE','STAFF')),
  total_sessions INTEGER NOT NULL DEFAULT 0,
  total_hours NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (total_hours >= 0),
  rate NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (rate >= 0),
  total_amount NUMERIC(16,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(source,employee_id,period)
);
-- Preserve employee salary-detail fields during initial legacy import and
-- catch-up on existing PostgreSQL volumes. No new reads from Core tables.
ALTER TABLE payroll_ledgers
  ADD COLUMN IF NOT EXISTS detail_rate NUMERIC(14,2);
ALTER TABLE payroll_ledgers
  ADD COLUMN IF NOT EXISTS detail_amount NUMERIC(16,2);

CREATE INDEX IF NOT EXISTS idx_payroll_employee_period
  ON payroll_ledgers(employee_id,period);

CREATE TABLE IF NOT EXISTS payroll_items (
  id VARCHAR(50) PRIMARY KEY,
  payroll_id VARCHAR(50) NOT NULL REFERENCES payroll_ledgers(id) ON DELETE CASCADE,
  schedule_id VARCHAR(50) NOT NULL,
  work_date DATE NOT NULL,
  source_type VARCHAR(50) NOT NULL,
  work_name VARCHAR(200) NOT NULL,
  hours NUMERIC(10,2) NOT NULL CHECK (hours >= 0),
  rate NUMERIC(14,2) NOT NULL CHECK (rate >= 0),
  amount NUMERIC(16,2) NOT NULL CHECK (amount >= 0),
  UNIQUE(payroll_id,schedule_id)
);
CREATE INDEX IF NOT EXISTS idx_payroll_items_date
  ON payroll_items(payroll_id,work_date);

CREATE TABLE IF NOT EXISTS payroll_meta (
  key VARCHAR(80) PRIMARY KEY,
  value VARCHAR(200) NOT NULL
);


-- Phase 8f-10: Payroll-owned teacher attendance evidence projection.
-- Financial amounts are NOT derived from it until the later signed writer
-- handoff. Source IDs/digests refer to Attendance immutable history; no
-- employee or financial PII is copied with an event.
CREATE TABLE IF NOT EXISTS payroll_attendance_reference_events (
  event_id BIGINT PRIMARY KEY CHECK(event_id>0),
  teaching_schedule_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  status VARCHAR(20),
  source_hash CHAR(64) NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT payroll_attendance_event_status CHECK (
    (event_kind='UPSERT' AND status IN ('PRESENT','ABSENT','LATE'))
    OR (event_kind='DELETE' AND status IS NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_payroll_attendance_reference_history
  ON payroll_attendance_reference_events(teaching_schedule_id,event_id);

CREATE TABLE IF NOT EXISTS payroll_attendance_reference_latest (
  teaching_schedule_id VARCHAR(50) PRIMARY KEY,
  event_id BIGINT NOT NULL CHECK(event_id>0),
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  status VARCHAR(20),
  source_hash CHAR(64) NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT payroll_attendance_latest_status CHECK (
    (event_kind='UPSERT' AND status IN ('PRESENT','ABSENT','LATE'))
    OR (event_kind='DELETE' AND status IS NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_payroll_attendance_reference_payable
  ON payroll_attendance_reference_latest(status)
  WHERE event_kind='UPSERT';

-- Separate history cursor, never MAX(latest.event_id): a later live event
-- must not cause an old historical page to be silently skipped.
CREATE TABLE IF NOT EXISTS payroll_attendance_reference_cursor (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
  last_event_id BIGINT NOT NULL DEFAULT 0 CHECK(last_event_id>=0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO payroll_attendance_reference_cursor(singleton,last_event_id)
VALUES(TRUE,0) ON CONFLICT(singleton) DO NOTHING;

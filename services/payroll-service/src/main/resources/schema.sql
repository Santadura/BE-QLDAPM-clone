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

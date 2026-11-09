-- Preserve the newer main-branch payroll workflow on the migration branch.
-- These are transitional core tables: the future Payroll bounded context must
-- migrate the salary ledger to its own database before core can be retired.
CREATE TABLE IF NOT EXISTS salary_rank_history (
  history_id VARCHAR(50) PRIMARY KEY,
  employee_id VARCHAR(50) NOT NULL REFERENCES employees(employee_id),
  previous_salary_rank_id VARCHAR(50),
  salary_rank_id VARCHAR(50) NOT NULL REFERENCES salary_ranks(salary_rank_id),
  amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
  effective_at TIMESTAMPTZ NOT NULL,
  review_request_id VARCHAR(50),
  approved_by VARCHAR(50),
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_salary_rank_history_employee
  ON salary_rank_history(employee_id,effective_at DESC,created_at DESC);

CREATE TABLE IF NOT EXISTS salary_review_requests (
  request_id VARCHAR(50) PRIMARY KEY,
  employee_id VARCHAR(50) NOT NULL REFERENCES employees(employee_id),
  current_salary_rank_id VARCHAR(50),
  proposed_salary_rank_id VARCHAR(50) NOT NULL REFERENCES salary_ranks(salary_rank_id),
  proposed_amount NUMERIC(14,2) NOT NULL CHECK (proposed_amount >= 0),
  requested_by VARCHAR(50),
  reason TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  decision_note TEXT,
  reviewed_by VARCHAR(50),
  requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_salary_reviews_employee ON salary_review_requests(employee_id,requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_salary_reviews_status ON salary_review_requests(status,requested_at DESC);

CREATE TABLE IF NOT EXISTS salary_calculation_items (
  item_id VARCHAR(50) PRIMARY KEY,
  payroll_source VARCHAR(20) NOT NULL CHECK (payroll_source IN ('EMPLOYEE','STAFF')),
  payroll_id VARCHAR(50) NOT NULL,
  schedule_id VARCHAR(50) NOT NULL,
  work_date DATE NOT NULL,
  work_type VARCHAR(50) NOT NULL,
  attendance_status VARCHAR(20) NOT NULL,
  worked_hours NUMERIC(8,2) NOT NULL CHECK (worked_hours >= 0),
  salary_rank_id VARCHAR(50),
  hourly_rate NUMERIC(14,2) NOT NULL CHECK (hourly_rate >= 0),
  pay_factor NUMERIC(8,2) NOT NULL DEFAULT 1,
  amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
  UNIQUE(payroll_source,payroll_id,schedule_id)
);
CREATE INDEX IF NOT EXISTS idx_salary_items_payroll
  ON salary_calculation_items(payroll_source,payroll_id,work_date);

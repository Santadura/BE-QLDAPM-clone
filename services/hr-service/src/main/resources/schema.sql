-- Certificates are now an independently owned HR write model.
-- Employee ownership remains in Core during the next HR migration phase.
CREATE TABLE IF NOT EXISTS hr_certificates (
  certificate_id VARCHAR(50) PRIMARY KEY,
  employee_id VARCHAR(50) NOT NULL,
  certificate_type VARCHAR(30) NOT NULL,
  score DOUBLE PRECISION,
  score_sw DOUBLE PRECISION,
  issue_date DATE,
  expiry_date DATE,
  issuer VARCHAR(100),
  certificate_url VARCHAR(500),
  status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_hr_certificates_employee
  ON hr_certificates(employee_id, created_at DESC);

CREATE TABLE IF NOT EXISTS hr_certificate_outbox (
  certificate_id VARCHAR(50) PRIMARY KEY REFERENCES hr_certificates(certificate_id),
  version BIGINT NOT NULL,
  delivered_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS hr_metadata (
  key VARCHAR(80) PRIMARY KEY,
  value VARCHAR(200) NOT NULL
);

-- SalaryRank writes are now owned by HR. Core salary_ranks is a versioned FK
-- reference projection used by the existing Payroll and employee workflows.
CREATE TABLE IF NOT EXISTS hr_salary_ranks (
  salary_rank_id VARCHAR(50) PRIMARY KEY,
  role_id VARCHAR(50) NOT NULL CHECK (role_id IN ('TEACHER','SALE','CS')),
  rank VARCHAR(50) NOT NULL,
  amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
  status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(role_id,rank)
);
CREATE TABLE IF NOT EXISTS hr_salary_rank_outbox (
  salary_rank_id VARCHAR(50) PRIMARY KEY REFERENCES hr_salary_ranks(salary_rank_id),
  version BIGINT NOT NULL,
  delivered_at TIMESTAMPTZ
);

-- Core salary_ranks now becomes the HR-owned write model's versioned
-- reference projection for employee foreign keys and payroll calculations.
CREATE TABLE hr_salary_rank_versions (
    salary_rank_id VARCHAR(50) PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

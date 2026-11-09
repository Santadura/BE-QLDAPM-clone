-- The catalog is the writer for course information after bootstrap.
-- The legacy academic courses table is a replicated reference used for FKs.
CREATE TABLE IF NOT EXISTS catalog_courses (
  course_id VARCHAR(50) PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  version BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS catalog_outbox (
  course_id VARCHAR(50) PRIMARY KEY REFERENCES catalog_courses(course_id),
  version BIGINT NOT NULL,
  delivered_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS catalog_meta (
  key VARCHAR(80) PRIMARY KEY,
  value VARCHAR(200) NOT NULL
);

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
);
CREATE INDEX IF NOT EXISTS academic_read_snapshots_actor
  ON academic_read_snapshots (username,role_id,synchronized_at DESC);

-- This is the actual Academic-owned class-planning read source. Core holds
-- the monotonic revision clock; Academic keeps a validated local snapshot,
-- refreshing only when Core has transactionally advanced that revision.
CREATE TABLE IF NOT EXISTS academic_planning_projection (
  name VARCHAR(20) PRIMARY KEY CHECK(name='planning'),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Shared per-class Academic read projection: never returned unless a
-- separate live Core class-level authorization and version check succeeds.
-- A global Core session mutation revision makes changes in ANY class
-- invalidate the cached candidate; revisions only move forward.
CREATE TABLE IF NOT EXISTS academic_session_projections (
  class_id VARCHAR(50) PRIMARY KEY,
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

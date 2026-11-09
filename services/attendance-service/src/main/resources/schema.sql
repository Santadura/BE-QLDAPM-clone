-- Attendance GET projection. No writes of actual teacher attendance are
-- performed by this service until the attendance writer saga is ready.
-- Every row is actor-scoped; no class/teacher roster is shared between CSs.
CREATE TABLE IF NOT EXISTS attendance_teacher_read_snapshots (
  snapshot_key CHAR(64) PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='array'),
  synchronized_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_attendance_teacher_actor
  ON attendance_teacher_read_snapshots(actor_username,class_id);

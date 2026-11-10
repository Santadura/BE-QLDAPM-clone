-- Core remains the exclusive teacher attendance writer in this phase.
-- The command receipt is committed atomically with attendance, audit and
-- the source outbox trigger. Duplicate keys do not repeat a business write.
CREATE TABLE IF NOT EXISTS teacher_attendance_command_receipts (
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  response_payload JSONB CHECK (response_payload IS NULL OR jsonb_typeof(response_payload)='object'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  PRIMARY KEY (actor_username,class_id,teaching_schedule_id,request_key),
  CONSTRAINT attendance_command_completion CHECK (
    (response_payload IS NULL AND completed_at IS NULL) OR
    (response_payload IS NOT NULL AND completed_at IS NOT NULL)
  )
);
CREATE INDEX IF NOT EXISTS idx_teacher_attendance_command_created
  ON teacher_attendance_command_receipts(created_at);

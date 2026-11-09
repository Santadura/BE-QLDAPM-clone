-- Phase 8b-3c: Core Academic transactional session leases for a future
-- independent Scheduling assignment writer. Leases are NOT assignment writes.
-- A future saga must renew/verify the lease before committing its own DB.
CREATE TABLE IF NOT EXISTS scheduling_academic_session_leases (
  lease_id VARCHAR(50) PRIMARY KEY,
  class_id VARCHAR(50) NOT NULL REFERENCES classes(class_id) ON DELETE CASCADE,
  class_session_id VARCHAR(50) NOT NULL REFERENCES class_sessions(class_session_id) ON DELETE CASCADE,
  expected_class_version BIGINT NOT NULL,
  expected_session_version BIGINT NOT NULL,
  command_key VARCHAR(128) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_scheduling_academic_command
  ON scheduling_academic_session_leases(class_session_id,command_key);
CREATE INDEX IF NOT EXISTS idx_scheduling_academic_lease_expiry
  ON scheduling_academic_session_leases(expires_at);
CREATE INDEX IF NOT EXISTS idx_scheduling_academic_active_leases
  ON scheduling_academic_session_leases(class_session_id,expires_at);
CREATE INDEX IF NOT EXISTS idx_scheduling_academic_active_class_leases
  ON scheduling_academic_session_leases(class_id,expires_at);

CREATE OR REPLACE FUNCTION iig_fence_leased_academic_lesson()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM scheduling_academic_session_leases l
    WHERE l.class_session_id=OLD.class_session_id AND l.expires_at>NOW()
  ) THEN
    RAISE EXCEPTION 'Academic lesson is reserved by an in-flight Scheduling command'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_leased_lesson_guard ON class_sessions;
CREATE TRIGGER trg_iig_leased_lesson_guard
  BEFORE UPDATE OF class_id,date,start_time,end_time,status OR DELETE
  ON class_sessions
  FOR EACH ROW EXECUTE FUNCTION iig_fence_leased_academic_lesson();

CREATE OR REPLACE FUNCTION iig_fence_leased_academic_class()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM scheduling_academic_session_leases l
    WHERE l.class_id=OLD.class_id AND l.expires_at>NOW()
  ) THEN
    RAISE EXCEPTION 'Class status or dates cannot change during an Academic session lease'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_leased_class_guard ON classes;
CREATE TRIGGER trg_iig_leased_class_guard
  BEFORE UPDATE OF status,start_date,end_date ON classes
  FOR EACH ROW
  WHEN (OLD.status IS DISTINCT FROM NEW.status OR
        OLD.start_date IS DISTINCT FROM NEW.start_date OR
        OLD.end_date IS DISTINCT FROM NEW.end_date)
  EXECUTE FUNCTION iig_fence_leased_academic_class();

-- Prevent another Core timetable path inserting an overlapping lesson while
-- the same class/date/time is reserved. Existing class_sessions uniqueness
-- already covers identical slots; this also covers partial overlaps.
CREATE OR REPLACE FUNCTION iig_fence_new_leased_academic_lesson()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM scheduling_academic_session_leases l
    JOIN class_sessions s ON s.class_session_id=l.class_session_id
    WHERE l.class_id=NEW.class_id AND l.expires_at>NOW()
      AND s.date=NEW.date AND s.start_time<NEW.end_time
      AND s.end_time>NEW.start_time
  ) THEN
    RAISE EXCEPTION 'New planned lesson overlaps a leased Academic session'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_leased_lesson_insert_guard ON class_sessions;
CREATE TRIGGER trg_iig_leased_lesson_insert_guard
  BEFORE INSERT ON class_sessions
  FOR EACH ROW EXECUTE FUNCTION iig_fence_new_leased_academic_lesson();

-- Never resume old Core assignment writers while a Scheduling Academic lease
-- is active. Mode switch is serialized with lease acquisition using the same
-- singleton row lock; interrupted commands still expire automatically.
CREATE OR REPLACE FUNCTION iig_block_assignment_resume_with_active_lease()
RETURNS TRIGGER LANGUAGE plpgsql AS $
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' AND EXISTS (
    SELECT 1 FROM scheduling_academic_session_leases
    WHERE expires_at>NOW()
  ) THEN
    RAISE EXCEPTION 'Cannot resume Core assignment writers with active Academic leases'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$;
DROP TRIGGER IF EXISTS trg_iig_assignment_resume_guard ON scheduling_assignment_writer_control;
CREATE TRIGGER trg_iig_assignment_resume_guard
  BEFORE UPDATE OF writer_mode ON scheduling_assignment_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_assignment_resume_with_active_lease();


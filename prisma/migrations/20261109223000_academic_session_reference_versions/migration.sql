-- Scheduling needs versioned Academic class and session facts before it can
-- safely own teacher and class-support assignment commands independently.
-- Academic is still Core-owned in phase 8: this is only the reference
-- contract, not cross-service ownership or a distributed lock.
ALTER TABLE classes
  ADD COLUMN IF NOT EXISTS scheduling_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE class_sessions
  ADD COLUMN IF NOT EXISTS scheduling_version BIGINT NOT NULL DEFAULT 1;

CREATE OR REPLACE FUNCTION iig_bump_assignment_academic_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.scheduling_version := OLD.scheduling_version + 1;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_academic_class_version ON classes;
CREATE TRIGGER trg_iig_academic_class_version
  BEFORE UPDATE OF status,start_date,end_date ON classes
  FOR EACH ROW
  WHEN (OLD.status IS DISTINCT FROM NEW.status OR
        OLD.start_date IS DISTINCT FROM NEW.start_date OR
        OLD.end_date IS DISTINCT FROM NEW.end_date)
  EXECUTE FUNCTION iig_bump_assignment_academic_version();

DROP TRIGGER IF EXISTS trg_iig_academic_session_version ON class_sessions;
CREATE TRIGGER trg_iig_academic_session_version
  BEFORE UPDATE OF class_id,date,start_time,end_time,status ON class_sessions
  FOR EACH ROW
  WHEN (OLD.class_id IS DISTINCT FROM NEW.class_id OR
        OLD.date IS DISTINCT FROM NEW.date OR
        OLD.start_time IS DISTINCT FROM NEW.start_time OR
        OLD.end_time IS DISTINCT FROM NEW.end_time OR
        OLD.status IS DISTINCT FROM NEW.status)
  EXECUTE FUNCTION iig_bump_assignment_academic_version();

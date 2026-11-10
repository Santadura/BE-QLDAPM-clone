-- Phase 8f-6: independent Attendance mutation fence, coordinated with
-- the existing Scheduling assignment fence. This is a PRE-CUTOVER guard:
-- only CORE and QUIESCED exist, not a second authoritative writer.
-- Never manually flip to a future ATTENDANCE mode without a complete saga.
CREATE TABLE IF NOT EXISTS teacher_attendance_writer_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  writer_mode VARCHAR(16) NOT NULL DEFAULT 'CORE'
    CHECK (writer_mode IN ('CORE','QUIESCED')),
  generation BIGINT NOT NULL DEFAULT 1 CHECK (generation>0),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO teacher_attendance_writer_control(singleton,writer_mode)
VALUES(TRUE,'CORE') ON CONFLICT(singleton) DO NOTHING;

-- The independent Attendance manifest must also detect a Scheduling
-- CORE -> QUIESCED -> CORE -> QUIESCED ABA transition during verification.
ALTER TABLE scheduling_assignment_writer_control
  ADD COLUMN IF NOT EXISTS writer_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE scheduling_assignment_writer_control
  ADD CONSTRAINT scheduling_writer_generation_positive CHECK(writer_generation>0);

CREATE OR REPLACE FUNCTION iig_advance_scheduling_writer_generation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.writer_mode IS DISTINCT FROM OLD.writer_mode THEN
    NEW.writer_generation := OLD.writer_generation + 1;
    NEW.changed_at := clock_timestamp();
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_schedule_writer_generation
  ON scheduling_assignment_writer_control;
CREATE TRIGGER trg_iig_schedule_writer_generation
  BEFORE UPDATE OF writer_mode ON scheduling_assignment_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_advance_scheduling_writer_generation();

-- The transition itself is serialized against the existing Scheduling
-- writer-control row: Scheduling must first stop changing assignments
-- before Attendance can be frozen. Restore in the reverse order.
CREATE OR REPLACE FUNCTION iig_attendance_writer_mode_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scheduling_mode VARCHAR(20);
BEGIN
  IF NEW.writer_mode IS DISTINCT FROM OLD.writer_mode THEN
    SELECT writer_mode INTO STRICT scheduling_mode
      FROM scheduling_assignment_writer_control
      WHERE singleton=TRUE FOR SHARE;
    IF scheduling_mode<>'QUIESCED' THEN
      RAISE EXCEPTION 'Freeze Scheduling before changing Attendance writer mode'
        USING ERRCODE='55000';
    END IF;
    NEW.generation := OLD.generation + 1;
    NEW.changed_at := clock_timestamp();
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_attendance_writer_transition
  ON teacher_attendance_writer_control;
CREATE TRIGGER trg_iig_attendance_writer_transition
  BEFORE UPDATE OF writer_mode ON teacher_attendance_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_attendance_writer_mode_transition();

-- Prevent resuming Scheduling assignment changes before unfreezing
-- Attendance. Without this guard the two control rows can diverge.
CREATE OR REPLACE FUNCTION iig_block_scheduling_resume_while_attendance_frozen()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attendance_mode VARCHAR(16);
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' THEN
    SELECT writer_mode INTO STRICT attendance_mode
      FROM teacher_attendance_writer_control
      WHERE singleton=TRUE FOR SHARE;
    IF attendance_mode<>'CORE' THEN
      RAISE EXCEPTION 'Unfreeze Attendance before resuming Scheduling writes'
        USING ERRCODE='55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_scheduling_attendance_resume_guard
  ON scheduling_assignment_writer_control;
CREATE TRIGGER trg_iig_scheduling_attendance_resume_guard
  BEFORE UPDATE OF writer_mode ON scheduling_assignment_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_scheduling_resume_while_attendance_frozen();

-- A BEFORE trigger acquires a shared control-row lock for the duration of
-- each Core writer transaction. A mode change waits for in-flight writes,
-- then rejects later writes before any row/audit/outbox change.
CREATE OR REPLACE FUNCTION iig_guard_teacher_attendance_quiescence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mode VARCHAR(16);
BEGIN
  SELECT writer_mode INTO STRICT mode
    FROM teacher_attendance_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF mode<>'CORE' THEN
    RAISE EXCEPTION 'Teacher Attendance Core writer is quiesced'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  IF TG_OP='TRUNCATE' THEN RETURN NULL; END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_attendance_domain_write_fence
  ON teacher_session_attendance;
CREATE TRIGGER trg_iig_attendance_domain_write_fence
  BEFORE INSERT OR UPDATE OR DELETE ON teacher_session_attendance
  FOR EACH ROW EXECUTE FUNCTION iig_guard_teacher_attendance_quiescence();
DROP TRIGGER IF EXISTS trg_iig_attendance_domain_truncate_fence
  ON teacher_session_attendance;
CREATE TRIGGER trg_iig_attendance_domain_truncate_fence
  BEFORE TRUNCATE ON teacher_session_attendance
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_teacher_attendance_quiescence();

-- Intended maintenance sequence:
--   UPDATE scheduling_assignment_writer_control SET writer_mode='QUIESCED' WHERE singleton;
--   UPDATE teacher_attendance_writer_control SET writer_mode='QUIESCED' WHERE singleton;
--   -- Verify frozen Attendance parity, do NOT enable a second writer yet.
--   UPDATE teacher_attendance_writer_control SET writer_mode='CORE' WHERE singleton;
--   UPDATE scheduling_assignment_writer_control SET writer_mode='CORE' WHERE singleton;
-- Future independent writes need an audited one-way handoff, not a new
-- unchecked SQL enum value or two simultaneous CORE writers.

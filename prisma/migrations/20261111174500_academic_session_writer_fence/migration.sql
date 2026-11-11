-- Phase 8j: ordered, transaction-local Academic/Core timetable writer fence.
-- This deliberately supports CORE and QUIESCED only. "ACADEMIC" may be
-- introduced ONLY after full Scheduling/Attendance/Academic saga verification.
-- The table row FOR SHARE serializes all Core session SQL writers against
-- a CORE->QUIESCED transition. Not a distributed cross-DB transaction.
CREATE TABLE IF NOT EXISTS academic_session_writer_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  writer_mode VARCHAR(16) NOT NULL DEFAULT 'CORE'
    CHECK(writer_mode IN ('CORE','QUIESCED')),
  generation BIGINT NOT NULL DEFAULT 1 CHECK(generation>0),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO academic_session_writer_control(singleton,writer_mode)
VALUES(TRUE,'CORE') ON CONFLICT(singleton) DO NOTHING;

CREATE OR REPLACE FUNCTION iig_guard_academic_writer_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE scheduling_mode VARCHAR(20);
DECLARE attendance_mode VARCHAR(20);
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Academic writer control cannot be deleted'
      USING ERRCODE='55000';
  END IF;
  IF NEW.writer_mode IS DISTINCT FROM OLD.writer_mode THEN
    -- Freeze in order: Scheduling -> Attendance -> Academic. Resume
    -- in reverse: Academic -> Attendance -> Scheduling.
    SELECT writer_mode INTO STRICT scheduling_mode
      FROM scheduling_assignment_writer_control
      WHERE singleton=TRUE FOR SHARE;
    SELECT writer_mode INTO STRICT attendance_mode
      FROM teacher_attendance_writer_control
      WHERE singleton=TRUE FOR SHARE;
    IF scheduling_mode<>'QUIESCED' OR attendance_mode<>'QUIESCED' THEN
      RAISE EXCEPTION 'Freeze Scheduling and Attendance before Academic transition'
        USING ERRCODE='55000';
    END IF;
    NEW.generation := OLD.generation+1;
    NEW.changed_at := clock_timestamp();
  ELSE
    IF NEW.generation IS DISTINCT FROM OLD.generation OR
       NEW.changed_at IS DISTINCT FROM OLD.changed_at OR
       NEW.singleton IS DISTINCT FROM OLD.singleton THEN
      RAISE EXCEPTION 'Academic writer generation is SQL guarded'
        USING ERRCODE='55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_academic_writer_transition
  ON academic_session_writer_control;
CREATE TRIGGER trg_iig_guard_academic_writer_transition
  BEFORE UPDATE OR DELETE ON academic_session_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_writer_transition();

CREATE OR REPLACE FUNCTION iig_require_core_academic_writer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM academic_session_writer_control
    WHERE singleton=TRUE AND writer_mode='CORE' FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Core Academic timetable writer is QUIESCED'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_require_core_academic_writer ON class_sessions;
CREATE TRIGGER trg_iig_require_core_academic_writer
  BEFORE INSERT OR UPDATE OR DELETE ON class_sessions
  FOR EACH ROW EXECUTE FUNCTION iig_require_core_academic_writer();

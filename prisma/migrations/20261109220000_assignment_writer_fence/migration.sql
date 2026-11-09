-- Phase 8b-3b: database-level fencing for ALL assignment writers.
-- Routing a controller is not enough: class lifecycle, attendance and
-- bulk staffing services also mutate these tables directly.
--
-- CORE is the only enabled writer today. QUIESCED is an operator-controlled
-- fail-closed maintenance state to hold ALL assignment writes (even direct SQL)
-- while validating data before a future ownership transfer. There is no
-- SCHEDULING state until the independent transactional write model exists.
CREATE TABLE IF NOT EXISTS scheduling_assignment_writer_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
  writer_mode VARCHAR(20) NOT NULL DEFAULT 'CORE'
    CHECK (writer_mode IN ('CORE','QUIESCED')),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO scheduling_assignment_writer_control(singleton,writer_mode)
VALUES (TRUE,'CORE') ON CONFLICT (singleton) DO NOTHING;

CREATE OR REPLACE FUNCTION iig_fence_core_assignment_writes()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mode VARCHAR(20);
BEGIN
  -- FOR SHARE serializes active writes with a future mode change.
  -- Acquiring QUIESCED waits for in-flight writers to finish, while writers
  -- entering afterwards observe the new mode and fail before changing rows.
  SELECT writer_mode INTO STRICT mode
    FROM scheduling_assignment_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF mode <> 'CORE' THEN
    RAISE EXCEPTION 'Core assignment writes are quiesced for ownership transfer'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_core_teacher_write_fence ON teaching_schedules;
CREATE TRIGGER trg_iig_core_teacher_write_fence
  BEFORE INSERT OR UPDATE OR DELETE ON teaching_schedules
  FOR EACH ROW EXECUTE FUNCTION iig_fence_core_assignment_writes();

DROP TRIGGER IF EXISTS trg_iig_core_staff_write_fence ON staff_schedules;
CREATE TRIGGER trg_iig_core_staff_write_fence
  BEFORE INSERT OR UPDATE OR DELETE ON staff_schedules
  FOR EACH ROW EXECUTE FUNCTION iig_fence_core_assignment_writes();

-- Teacher attendance is tied to the authoritative teaching assignment
-- and must be frozen with it. Without this additional trigger, attendance
-- could change during the Core-to-Scheduling parity snapshot even while the
-- two assignment tables were already quiesced.
DROP TRIGGER IF EXISTS trg_iig_core_attendance_write_fence ON teacher_session_attendance;
CREATE TRIGGER trg_iig_core_attendance_write_fence
  BEFORE INSERT OR UPDATE OR DELETE ON teacher_session_attendance
  FOR EACH ROW EXECUTE FUNCTION iig_fence_core_assignment_writes();

-- Operator rollback (only when coordinating a cutover):
-- UPDATE scheduling_assignment_writer_control
-- SET writer_mode='CORE',changed_at=NOW() WHERE singleton=TRUE;

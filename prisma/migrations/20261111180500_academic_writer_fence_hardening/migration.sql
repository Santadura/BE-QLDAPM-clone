-- Phase 8j hardening: TRUNCATE bypasses row-level immutable event and
-- writer fences. Reject it unconditionally on both the source and writer
-- control tables. Core timetable changes must produce source events.
CREATE OR REPLACE FUNCTION iig_reject_academic_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'TRUNCATE bypasses immutable Academic session history'
    USING ERRCODE='55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_reject_academic_session_truncate
  ON class_sessions;
CREATE TRIGGER trg_iig_reject_academic_session_truncate
  BEFORE TRUNCATE ON class_sessions
  FOR EACH STATEMENT EXECUTE FUNCTION iig_reject_academic_truncate();

DROP TRIGGER IF EXISTS trg_iig_reject_academic_outbox_truncate
  ON academic_session_source_outbox;
CREATE TRIGGER trg_iig_reject_academic_outbox_truncate
  BEFORE TRUNCATE ON academic_session_source_outbox
  FOR EACH STATEMENT EXECUTE FUNCTION iig_reject_academic_truncate();

DROP TRIGGER IF EXISTS trg_iig_reject_academic_writer_control_truncate
  ON academic_session_writer_control;
CREATE TRIGGER trg_iig_reject_academic_writer_control_truncate
  BEFORE TRUNCATE ON academic_session_writer_control
  FOR EACH STATEMENT EXECUTE FUNCTION iig_reject_academic_truncate();

-- Restore in the reverse freeze order. With Academic QUIESCED, Attendance
-- cannot return to CORE, which in turn blocks Scheduling's resume guard.
-- Never allow a temporarily frozen Academic source to silently become
-- inconsistent with an already-reopened Attendance writer.
CREATE OR REPLACE FUNCTION iig_block_attendance_resume_while_academic_frozen()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE academic_mode VARCHAR(16);
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' THEN
    SELECT writer_mode INTO STRICT academic_mode
      FROM academic_session_writer_control
      WHERE singleton=TRUE FOR SHARE;
    IF academic_mode<>'CORE' THEN
      RAISE EXCEPTION 'Resume Academic before resuming Attendance'
        USING ERRCODE='55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_block_attendance_resume_academic_frozen
  ON teacher_attendance_writer_control;
CREATE TRIGGER trg_iig_block_attendance_resume_academic_frozen
  BEFORE UPDATE OF writer_mode ON teacher_attendance_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_attendance_resume_while_academic_frozen();

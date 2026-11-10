-- Phase 8f-1: durable Core change feed before Attendance assumes write ownership.
-- The Core teacher_session_attendance table remains authoritative in this
-- phase. No direct cross-database transaction is introduced.
CREATE TABLE IF NOT EXISTS teacher_attendance_source_outbox (
  event_id BIGSERIAL PRIMARY KEY,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_teacher_attendance_pending
  ON teacher_attendance_source_outbox(event_id)
  WHERE delivered_at IS NULL;

-- Protect the initial snapshot from concurrent writes. Every row that
-- existed before the trigger is represented by a replayable UPSERT event.
LOCK TABLE teacher_session_attendance IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO teacher_attendance_source_outbox
    (teaching_schedule_id,event_kind,payload)
SELECT teaching_schedule_id,'UPSERT',to_jsonb(t)
  FROM teacher_session_attendance t ORDER BY teaching_schedule_id;

CREATE OR REPLACE FUNCTION iig_capture_teacher_attendance_source()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    INSERT INTO teacher_attendance_source_outbox
      (teaching_schedule_id,event_kind,payload)
    VALUES(OLD.teaching_schedule_id,'DELETE',to_jsonb(OLD));
    RETURN OLD;
  END IF;
  INSERT INTO teacher_attendance_source_outbox
    (teaching_schedule_id,event_kind,payload)
  VALUES(NEW.teaching_schedule_id,'UPSERT',to_jsonb(NEW));
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_capture_teacher_attendance_source
  ON teacher_session_attendance;
CREATE TRIGGER trg_iig_capture_teacher_attendance_source
  AFTER INSERT OR UPDATE OR DELETE ON teacher_session_attendance
  FOR EACH ROW EXECUTE FUNCTION iig_capture_teacher_attendance_source();

-- Original event identity/payload and a committed delivery timestamp are
-- immutable. The ONLY permitted update is a single NULL -> delivery time.
CREATE OR REPLACE FUNCTION iig_guard_teacher_attendance_outbox()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE'
     OR NEW.event_id IS DISTINCT FROM OLD.event_id
     OR NEW.teaching_schedule_id IS DISTINCT FROM OLD.teaching_schedule_id
     OR NEW.event_kind IS DISTINCT FROM OLD.event_kind
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR (OLD.delivered_at IS NOT NULL
         AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at)
  THEN
    RAISE EXCEPTION 'Committed teacher attendance source event is immutable'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_teacher_attendance_outbox
  ON teacher_attendance_source_outbox;
CREATE TRIGGER trg_iig_guard_teacher_attendance_outbox
  BEFORE UPDATE OR DELETE ON teacher_attendance_source_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_guard_teacher_attendance_outbox();

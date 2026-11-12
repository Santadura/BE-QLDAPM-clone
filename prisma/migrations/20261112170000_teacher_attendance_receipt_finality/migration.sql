-- A completed Core attendance receipt is an immutable outcome used to
-- reconcile another database after network loss. Never permit an operator
-- or a duplicated request to replace its committed response or identity.
-- A PENDING receipt may advance ONCE to a completed outcome inside the
-- original Core attendance/audit/outbox transaction.
CREATE OR REPLACE FUNCTION iig_teacher_attendance_receipt_finality()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'TRUNCATE' OR TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Core attendance command receipts are durable and immutable'
      USING ERRCODE='55000';
  END IF;

  IF OLD.response_payload IS NOT NULL OR OLD.completed_at IS NOT NULL
     OR NEW.actor_username IS DISTINCT FROM OLD.actor_username
     OR NEW.class_id IS DISTINCT FROM OLD.class_id
     OR NEW.teaching_schedule_id IS DISTINCT FROM OLD.teaching_schedule_id
     OR NEW.request_key IS DISTINCT FROM OLD.request_key
     OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
     OR NEW.response_payload IS NULL OR NEW.completed_at IS NULL THEN
    RAISE EXCEPTION 'Only the first committed attendance receipt outcome may be recorded'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_attendance_receipt_update
  ON teacher_attendance_command_receipts;
CREATE TRIGGER trg_iig_attendance_receipt_update
  BEFORE UPDATE ON teacher_attendance_command_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_teacher_attendance_receipt_finality();

DROP TRIGGER IF EXISTS trg_iig_attendance_receipt_delete
  ON teacher_attendance_command_receipts;
CREATE TRIGGER trg_iig_attendance_receipt_delete
  BEFORE DELETE ON teacher_attendance_command_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_teacher_attendance_receipt_finality();

DROP TRIGGER IF EXISTS trg_iig_attendance_receipt_truncate
  ON teacher_attendance_command_receipts;
CREATE TRIGGER trg_iig_attendance_receipt_truncate
  BEFORE TRUNCATE ON teacher_attendance_command_receipts
  FOR EACH STATEMENT EXECUTE FUNCTION iig_teacher_attendance_receipt_finality();

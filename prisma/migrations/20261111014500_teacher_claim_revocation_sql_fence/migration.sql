-- Phase 8g-13c: enforce an audit BEFORE an orphan Core writer claim can
-- enter REVOKED. No alternate SQL path may turn a pending or confirmed
-- writer claim into "revoked" without an immutable, exact administrator/
-- reason audit row. No UPDATE of an already revoked claim is permitted.
CREATE OR REPLACE FUNCTION iig_guard_teacher_writer_claim_revocation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.status='REVOKED' THEN
      RAISE EXCEPTION 'Cannot insert a pre-revoked teacher writer claim'
        USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.status='REVOKED' THEN
    IF NEW IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'Revoked Core teacher claim evidence is immutable'
        USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.status='REVOKED' THEN
    IF OLD.status<>'PENDING' OR NEW.operation<>'ASSIGN_TEACHER'
      OR NEW.revoked_at IS NULL OR NEW.revoked_by IS NULL
      OR NEW.revocation_reason IS NULL
      OR NOT EXISTS (
        SELECT 1 FROM scheduling_teacher_claim_revocation_audit a
        WHERE a.actor_username=NEW.actor_username
          AND a.operation=NEW.operation
          AND a.request_key=NEW.request_key
          AND a.admin_username=NEW.revoked_by
          AND a.reason=NEW.revocation_reason
      ) THEN
      RAISE EXCEPTION 'Core teacher claim revocation requires matching immutable audit'
        USING ERRCODE='55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_teacher_writer_claim_revocation
  ON scheduling_assignment_writer_claims;
CREATE TRIGGER trg_iig_teacher_writer_claim_revocation
  BEFORE INSERT OR UPDATE ON scheduling_assignment_writer_claims
  FOR EACH ROW EXECUTE FUNCTION iig_guard_teacher_writer_claim_revocation();

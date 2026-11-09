-- Phase 8d-3: once an independent Scheduling assignment reference
-- was committed, reverting Core to direct assignment writes is NOT a
-- safe one-line rollback. Core's reference rows might lag Scheduling's
-- authoritative revisions or have pending compensation.
--
-- Deliberate handback requires full Core/Scheduling comparison and
-- an explicit vetted rollback migration. This persistent guard prevents
-- the standard maintenance script (or manual operator error) from simply
-- switching Core back on and creating two active assignment writers.
CREATE OR REPLACE FUNCTION iig_block_assignment_resume_with_active_lease()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' THEN
    IF EXISTS (
      SELECT 1 FROM scheduling_academic_session_leases
      WHERE expires_at>NOW()
    ) THEN
      RAISE EXCEPTION 'Cannot resume Core writes with active Academic leases'
        USING ERRCODE='55000';
    END IF;
    IF EXISTS (
      SELECT 1 FROM scheduling_assignment_reference_receipts
    ) THEN
      RAISE EXCEPTION 'Core handback requires audited Scheduling reference reconciliation'
        USING ERRCODE='55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_assignment_resume_guard
  ON scheduling_assignment_writer_control;
CREATE TRIGGER trg_iig_assignment_resume_guard
  BEFORE UPDATE OF writer_mode ON scheduling_assignment_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_assignment_resume_with_active_lease();

-- Phase 8g-13: durable and auditable claim REVOKED only when a
-- previously STAGED Scheduling teacher command has been irreversibly
-- REJECTED with no local source event. The private Core controller checks
-- live ADMIN auth and Scheduling proof under Core's writer-mode fence.
-- The claim stays in history forever; no DELETE of evidence is allowed.
ALTER TABLE scheduling_assignment_writer_claims
  DROP CONSTRAINT IF EXISTS scheduling_assignment_writer_claims_status_check;
ALTER TABLE scheduling_assignment_writer_claims
  ADD CONSTRAINT scheduling_assignment_writer_claims_status_check
  CHECK(status IN ('PENDING','CONFIRMED','REVOKED'));
ALTER TABLE scheduling_assignment_writer_claims
  ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;
ALTER TABLE scheduling_assignment_writer_claims
  ADD COLUMN IF NOT EXISTS revoked_by VARCHAR(100);
ALTER TABLE scheduling_assignment_writer_claims
  ADD COLUMN IF NOT EXISTS revocation_reason VARCHAR(300);

CREATE TABLE IF NOT EXISTS scheduling_teacher_claim_revocation_audit (
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(40) NOT NULL DEFAULT 'ASSIGN_TEACHER'
    CHECK(operation='ASSIGN_TEACHER'),
  request_key VARCHAR(128) NOT NULL,
  admin_username VARCHAR(100) NOT NULL,
  reason VARCHAR(300) NOT NULL CHECK(length(reason)>=20),
  revoked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(actor_username,operation,request_key),
  FOREIGN KEY(actor_username,operation,request_key)
    REFERENCES scheduling_assignment_writer_claims(actor_username,operation,request_key)
);

-- The evidence stays immutable even when a later operator attempts a
-- direct SQL modification or deletion.
CREATE OR REPLACE FUNCTION iig_guard_teacher_claim_revocation_audit()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Teacher claim revocation audit is immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_teacher_claim_revocation_audit
  ON scheduling_teacher_claim_revocation_audit;
CREATE TRIGGER trg_iig_teacher_claim_revocation_audit
  BEFORE UPDATE OR DELETE ON scheduling_teacher_claim_revocation_audit
  FOR EACH ROW EXECUTE FUNCTION iig_guard_teacher_claim_revocation_audit();

-- Handback may ignore ONLY explicitly REVOKED orphan claims. All PENDING
-- and CONFIRMED claims, live Academic leases and any Scheduling-origin
-- reference receipt still block Core writer resumption. This is not a
-- blanket "switch Core back" bypass.
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
      SELECT 1 FROM scheduling_assignment_writer_claims
      WHERE status IN ('PENDING','CONFIRMED')
    ) THEN
      RAISE EXCEPTION 'Core handback blocked by unresolved Scheduling writer claims'
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

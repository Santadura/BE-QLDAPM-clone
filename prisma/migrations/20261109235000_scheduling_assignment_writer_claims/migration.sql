-- Phase 8d-5: durable Core-side admission for independent Scheduling commands.
--
-- A Core QUIESCED -> CORE handback must not succeed between Scheduling's
-- preflight mode read and its independent local commit. The Scheduling service
-- obtains this durable claim BEFORE writing its own database. The claim
-- transaction serializes against writer-mode transitions using the same
-- singleton row lock as Academic session leases.
--
-- Claims intentionally do NOT expire automatically. If Scheduling crashes
-- between acquiring the claim and creating a local event, a handback must
-- fail closed until an operator compares Core and Scheduling and uses an
-- explicit audited recovery migration. Lease TTL alone is not sufficient.
CREATE TABLE IF NOT EXISTS scheduling_assignment_writer_claims (
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(40) NOT NULL
    CHECK (operation IN ('ASSIGN_STAFF','EDIT_STAFF','CANCEL_STAFF')),
  request_key VARCHAR(128) NOT NULL,
  manager_employee_id VARCHAR(50) NOT NULL,
  claimed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','CONFIRMED')),
  confirmed_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,operation,request_key)
);

-- The existing handback guard is replaced (not bypassed): independent
-- Academic leases, Core reference receipts AND acquired command claims
-- independently prevent restarting legacy Core assignment writes.
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
    ) THEN
      RAISE EXCEPTION 'Core handback blocked by durable Scheduling command claims'
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

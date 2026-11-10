-- Phase 8g-5: TC-owned TEACHING assignment writer claims.
-- This does NOT allow a public or independent teacher write by itself.
-- The same append-only Core claim still permanently fences handback
-- until its Scheduling transaction and reference are reconciled.
ALTER TABLE scheduling_assignment_writer_claims
  DROP CONSTRAINT IF EXISTS scheduling_assignment_writer_claims_operation_check;
ALTER TABLE scheduling_assignment_writer_claims
  ADD CONSTRAINT scheduling_assignment_writer_claims_operation_check
  CHECK (operation IN ('ASSIGN_STAFF','EDIT_STAFF','CANCEL_STAFF','ASSIGN_TEACHER'));

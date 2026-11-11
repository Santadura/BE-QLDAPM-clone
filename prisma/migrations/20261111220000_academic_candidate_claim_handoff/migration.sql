-- Phase 8o: Core durable Academic candidate claim and HAND-BACK FENCE.
-- A candidate is rehearsal-only; this claim is not Academic write authority.
-- The Core writer cannot resume until every cross-DB claim was released
-- with exact durable Academic candidate/outbox evidence.
CREATE TABLE IF NOT EXISTS academic_candidate_claims (
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  class_id VARCHAR(50) NOT NULL,
  class_session_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  academic_generation BIGINT NOT NULL CHECK(academic_generation>0),
  attendance_generation BIGINT NOT NULL CHECK(attendance_generation>0),
  scheduling_generation BIGINT NOT NULL CHECK(scheduling_generation>0),
  state VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','RELEASED')),
  candidate_command_id UUID,
  candidate_outbox_hash CHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  released_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,request_key),
  CONSTRAINT academic_candidate_release_consistent CHECK(
    (state='OPEN' AND candidate_command_id IS NULL
      AND candidate_outbox_hash IS NULL AND released_at IS NULL) OR
    (state='RELEASED' AND candidate_command_id IS NOT NULL
      AND candidate_outbox_hash ~ '^[0-9a-f]{64}$'
      AND released_at IS NOT NULL))
);

-- At most one unresolved proposal may hold a Core-side session claim.
-- Multiple operator keys for one lesson cannot race a future handback.
CREATE UNIQUE INDEX IF NOT EXISTS academic_candidate_claim_one_open_session
  ON academic_candidate_claims(class_session_id) WHERE state='OPEN';

CREATE TABLE IF NOT EXISTS academic_candidate_claim_audit (
  audit_id BIGSERIAL PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('ACQUIRED','RELEASED')),
  candidate_command_id UUID,
  candidate_outbox_hash CHAR(64),
  written_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS academic_candidate_claim_audit_by_scope
  ON academic_candidate_claim_audit(actor_username,request_key,audit_id);

CREATE OR REPLACE FUNCTION iig_guard_academic_candidate_claim_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Academic candidate claim cannot be deleted'
      USING ERRCODE='55000';
  END IF;
  IF OLD.state='OPEN' AND NEW.state='RELEASED' AND
      NEW.actor_username=OLD.actor_username AND
      NEW.request_key=OLD.request_key AND
      NEW.request_hash=OLD.request_hash AND
      NEW.class_id=OLD.class_id AND
      NEW.class_session_id=OLD.class_session_id AND
      NEW.source_event_id=OLD.source_event_id AND
      NEW.source_revision=OLD.source_revision AND
      NEW.academic_generation=OLD.academic_generation AND
      NEW.attendance_generation=OLD.attendance_generation AND
      NEW.scheduling_generation=OLD.scheduling_generation AND
      NEW.created_at=OLD.created_at AND
      NEW.released_at IS NOT NULL AND
      NEW.candidate_command_id IS NOT NULL AND
      NEW.candidate_outbox_hash ~ '^[0-9a-f]{64}$' THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'Academic candidate claims are immutable after release'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_academic_candidate_claim
  ON academic_candidate_claims;
CREATE TRIGGER trg_iig_guard_academic_candidate_claim
  BEFORE UPDATE OR DELETE ON academic_candidate_claims
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_candidate_claim_state();

CREATE OR REPLACE FUNCTION iig_guard_academic_claim_audit_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic candidate claim history is immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_academic_candidate_audit_mutate
  ON academic_candidate_claim_audit;
CREATE TRIGGER trg_iig_guard_academic_candidate_audit_mutate
  BEFORE UPDATE OR DELETE ON academic_candidate_claim_audit
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_claim_audit_immutable();
DROP TRIGGER IF EXISTS trg_iig_guard_academic_candidate_claim_truncate
  ON academic_candidate_claims;
CREATE TRIGGER trg_iig_guard_academic_candidate_claim_truncate
  BEFORE TRUNCATE ON academic_candidate_claims
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_academic_claim_audit_immutable();
DROP TRIGGER IF EXISTS trg_iig_guard_academic_candidate_audit_truncate
  ON academic_candidate_claim_audit;
CREATE TRIGGER trg_iig_guard_academic_candidate_audit_truncate
  BEFORE TRUNCATE ON academic_candidate_claim_audit
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_academic_claim_audit_immutable();

-- An in-flight Core claim freezes writer handback even if all source
-- outboxes happen to be empty. PostgreSQL row lock on writer_control
-- serializes claim acquisition with this trigger's mode transition.
CREATE OR REPLACE FUNCTION iig_guard_academic_candidate_claim_handback()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' AND
      EXISTS(SELECT 1 FROM academic_candidate_claims WHERE state='OPEN') THEN
    RAISE EXCEPTION 'Unreleased Academic candidate claim prevents Core handback'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_guard_academic_candidate_claim_handback
  ON academic_session_writer_control;
CREATE TRIGGER trg_iig_guard_academic_candidate_claim_handback
  BEFORE UPDATE OF writer_mode ON academic_session_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_candidate_claim_handback();

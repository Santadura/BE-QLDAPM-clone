-- Phase 8v: durable private Core handback barrier for Target/Result
-- NON-AUTHORITATIVE candidate rehearsal. This does not grant Academic
-- domain write authority. A claim without committed local Academic evidence
-- MUST remain OPEN; Core handback is then blocked until operator recovery.
BEGIN;

CREATE TABLE academic_roster_candidate_claims (
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
  entity_type VARCHAR(20) NOT NULL CHECK(entity_type IN ('TARGET','RESULT')),
  entity_id VARCHAR(50) NOT NULL,
  source_event_id BIGINT NOT NULL CHECK(source_event_id>0),
  source_revision BIGINT NOT NULL CHECK(source_revision>0),
  writer_generation BIGINT NOT NULL CHECK(writer_generation>=2),
  state VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','RELEASED')),
  candidate_command_id UUID,
  candidate_outbox_hash CHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  released_at TIMESTAMPTZ,
  PRIMARY KEY(actor_username,request_key),
  CONSTRAINT iig_roster_claim_release_complete CHECK (
    (state='OPEN' AND candidate_command_id IS NULL AND
      candidate_outbox_hash IS NULL AND released_at IS NULL) OR
    (state='RELEASED' AND candidate_command_id IS NOT NULL AND
      candidate_outbox_hash ~ '^[0-9a-f]{64}$' AND released_at IS NOT NULL))
);
CREATE UNIQUE INDEX academic_roster_claim_one_open_entity
  ON academic_roster_candidate_claims(entity_type,entity_id)
  WHERE state='OPEN';

CREATE TABLE academic_roster_candidate_claim_audit (
  audit_id BIGSERIAL PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('ACQUIRED','RELEASED')),
  candidate_command_id UUID,
  candidate_outbox_hash CHAR(64),
  written_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX academic_roster_claim_audit_scope
  ON academic_roster_candidate_claim_audit(actor_username,request_key,audit_id);

-- A direct SQL insert cannot bypass Core's writer generation, even if
-- the private REST handler was not involved. Its FOR SHARE lock serializes
-- claim creation with Core QUIESCED->CORE transitions.
CREATE OR REPLACE FUNCTION iig_roster_claim_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  mode_now TEXT;
  generation_now BIGINT;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Academic roster candidate claim is immutable'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='INSERT' THEN
    SELECT writer_mode,generation INTO STRICT mode_now,generation_now
      FROM academic_roster_writer_control
      WHERE singleton=TRUE FOR SHARE;
    IF mode_now<>'QUIESCED' OR generation_now<>NEW.writer_generation THEN
      RAISE EXCEPTION 'Academic roster claim requires current frozen writer generation'
        USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state='OPEN' AND NEW.state='RELEASED' AND
     NEW.actor_username=OLD.actor_username AND
     NEW.request_key=OLD.request_key AND
     NEW.request_hash=OLD.request_hash AND
     NEW.entity_type=OLD.entity_type AND
     NEW.entity_id=OLD.entity_id AND
     NEW.source_event_id=OLD.source_event_id AND
     NEW.source_revision=OLD.source_revision AND
     NEW.writer_generation=OLD.writer_generation AND
     NEW.created_at=OLD.created_at AND
     NEW.candidate_command_id IS NOT NULL AND
     NEW.candidate_outbox_hash ~ '^[0-9a-f]{64}$' AND
     NEW.released_at IS NOT NULL THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'Academic roster candidate claim transition forbidden'
    USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER trg_iig_roster_claim_immutable
  BEFORE INSERT OR UPDATE OR DELETE ON academic_roster_candidate_claims
  FOR EACH ROW EXECUTE FUNCTION iig_roster_claim_guard();
CREATE TRIGGER trg_iig_roster_claim_no_truncate
  BEFORE TRUNCATE ON academic_roster_candidate_claims
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_immutable_roster_writer_audit();

-- SQL source of truth for both immutable ACQUIRED and RELEASED audits.
CREATE OR REPLACE FUNCTION iig_roster_claim_audit_append()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    INSERT INTO academic_roster_candidate_claim_audit
      (actor_username,request_key,request_hash,event_kind)
    VALUES(NEW.actor_username,NEW.request_key,NEW.request_hash,'ACQUIRED');
  ELSIF TG_OP='UPDATE' AND NEW.state='RELEASED' AND OLD.state='OPEN' THEN
    INSERT INTO academic_roster_candidate_claim_audit
      (actor_username,request_key,request_hash,event_kind,
       candidate_command_id,candidate_outbox_hash)
    VALUES(NEW.actor_username,NEW.request_key,NEW.request_hash,'RELEASED',
      NEW.candidate_command_id,NEW.candidate_outbox_hash);
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER trg_iig_roster_claim_audit_append
  AFTER INSERT OR UPDATE ON academic_roster_candidate_claims
  FOR EACH ROW EXECUTE FUNCTION iig_roster_claim_audit_append();
CREATE TRIGGER trg_iig_roster_claim_audit_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_candidate_claim_audit
  FOR EACH ROW EXECUTE FUNCTION iig_require_immutable_roster_writer_audit();
CREATE TRIGGER trg_iig_roster_claim_audit_no_truncate
  BEFORE TRUNCATE ON academic_roster_candidate_claim_audit
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_immutable_roster_writer_audit();

-- Preserve frozen Core state whenever ANY candidate remains OPEN.
-- A successful Academic outbox commit is necessary for claim release
-- and never on its own opens the Core writer.
CREATE OR REPLACE FUNCTION iig_block_roster_claim_handback()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' AND
     EXISTS(SELECT 1 FROM academic_roster_candidate_claims WHERE state='OPEN') THEN
    RAISE EXCEPTION 'Unreleased Academic roster candidate claim prevents Core handback'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_iig_roster_claim_handback
  BEFORE UPDATE OF writer_mode ON academic_roster_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_roster_claim_handback();
COMMIT;

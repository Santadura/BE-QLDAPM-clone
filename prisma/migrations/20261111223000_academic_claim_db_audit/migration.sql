-- Phase 8s: every Core Academic candidate claim INSERT or RELEASED state
-- transition MUST append its immutable audit inside the SAME PG statement.
-- Service-level auditing alone lets direct SQL state updates evade evidence.
-- Existing historical ACQUIRED/RELEASED audit rows are retained.
CREATE UNIQUE INDEX IF NOT EXISTS academic_candidate_claim_one_audit_per_transition
 ON academic_candidate_claim_audit(actor_username,request_key,event_kind);

CREATE OR REPLACE FUNCTION iig_audit_academic_claim_state_in_database()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    INSERT INTO academic_candidate_claim_audit
      (actor_username,request_key,request_hash,event_kind)
    VALUES(NEW.actor_username,NEW.request_key,NEW.request_hash,'ACQUIRED');
  ELSIF TG_OP='UPDATE' AND OLD.state='OPEN' AND NEW.state='RELEASED' THEN
    INSERT INTO academic_candidate_claim_audit
      (actor_username,request_key,request_hash,event_kind,
       candidate_command_id,candidate_outbox_hash)
    VALUES(NEW.actor_username,NEW.request_key,NEW.request_hash,'RELEASED',
      NEW.candidate_command_id,NEW.candidate_outbox_hash);
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_candidate_claim_durable_transition_audit
 ON academic_candidate_claims;
CREATE TRIGGER trg_iig_candidate_claim_durable_transition_audit
 AFTER INSERT OR UPDATE ON academic_candidate_claims
 FOR EACH ROW EXECUTE FUNCTION iig_audit_academic_claim_state_in_database();

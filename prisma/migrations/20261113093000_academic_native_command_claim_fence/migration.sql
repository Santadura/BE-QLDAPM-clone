-- Core-side durable, class-scoped admission lease for native Academic
-- timetable operations. Native rehearsal commits in a DIFFERENT PostgreSQL.
-- The Core claim must survive JVM death or an ambiguous HTTP response.
-- An OPEN claim is never automatically expired/deleted/compensated: Core
-- cannot resume while the original remote mutation may have committed.
BEGIN;

CREATE TABLE academic_native_command_claims (
  actor_username VARCHAR(100) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  operation VARCHAR(20) NOT NULL CHECK (operation IN ('PLAN','RESCHEDULE')),
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  session_generation BIGINT NOT NULL CHECK (session_generation>0),
  roster_generation BIGINT NOT NULL CHECK (roster_generation>0),
  attendance_generation BIGINT NOT NULL CHECK (attendance_generation>0),
  scheduling_generation BIGINT NOT NULL CHECK (scheduling_generation>0),
  state VARCHAR(12) NOT NULL DEFAULT 'OPEN'
    CHECK (state IN ('OPEN','RELEASED')),
  event_id UUID,
  outbox_hash CHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  released_at TIMESTAMPTZ,
  PRIMARY KEY (actor_username,operation,request_key),
  CONSTRAINT academic_native_claim_release_proof CHECK (
    (state='OPEN' AND event_id IS NULL AND outbox_hash IS NULL AND released_at IS NULL)
    OR
    (state='RELEASED' AND event_id IS NOT NULL AND
     outbox_hash ~ '^[0-9a-f]{64}$' AND released_at IS NOT NULL)
  )
);

-- A class may not have two independent live native mutations, even when
-- different actors or idempotency keys are used.
CREATE UNIQUE INDEX academic_native_one_open_class_claim
  ON academic_native_command_claims(class_id) WHERE state='OPEN';

CREATE TABLE academic_native_command_claim_audit (
  audit_id BIGSERIAL PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  operation VARCHAR(20) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(12) NOT NULL CHECK(event_kind IN ('ACQUIRED','RELEASED')),
  event_id UUID,
  outbox_hash CHAR(64),
  written_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION iig_guard_native_academic_claim()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
 session_mode TEXT;
 roster_mode TEXT;
 attendance_mode TEXT;
 scheduling_mode TEXT;
 session_gen BIGINT;
 roster_gen BIGINT;
 attendance_gen BIGINT;
 scheduling_gen BIGINT;
 class_count BIGINT;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Core native Academic claims cannot be deleted'
      USING ERRCODE='55000';
  END IF;

  IF TG_OP='UPDATE' THEN
    IF OLD.state='OPEN' AND NEW.state='RELEASED' AND
       OLD.actor_username=NEW.actor_username AND
       OLD.class_id=NEW.class_id AND
       OLD.operation=NEW.operation AND
       OLD.request_key=NEW.request_key AND
       OLD.request_hash=NEW.request_hash AND
       OLD.session_generation=NEW.session_generation AND
       OLD.roster_generation=NEW.roster_generation AND
       OLD.attendance_generation=NEW.attendance_generation AND
       OLD.scheduling_generation=NEW.scheduling_generation AND
       OLD.created_at=NEW.created_at AND
       NEW.event_id IS NOT NULL AND NEW.outbox_hash ~ '^[0-9a-f]{64}$' AND
       NEW.released_at IS NOT NULL THEN
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Core native Academic claim state and identity are immutable'
      USING ERRCODE='55000';
  END IF;

  -- Statement order matches the Core Academic row writers: sessions,
  -- roster, Scheduling then Attendance. Share locks serialize claim
  -- admission with the transactional Core writer handback controls.
  SELECT writer_mode,generation INTO STRICT session_mode,session_gen
    FROM academic_session_writer_control WHERE singleton=TRUE FOR SHARE;
  SELECT writer_mode,generation INTO STRICT roster_mode,roster_gen
    FROM academic_roster_writer_control WHERE singleton=TRUE FOR SHARE;
  SELECT writer_mode,writer_generation
    INTO STRICT scheduling_mode,scheduling_gen
    FROM scheduling_assignment_writer_control WHERE singleton=TRUE FOR SHARE;
  SELECT writer_mode,generation INTO STRICT attendance_mode,attendance_gen
    FROM teacher_attendance_writer_control WHERE singleton=TRUE FOR SHARE;
  IF session_mode<>'QUIESCED' OR roster_mode<>'QUIESCED' OR
     scheduling_mode<>'QUIESCED' OR attendance_mode<>'QUIESCED' OR
     session_gen<>NEW.session_generation OR roster_gen<>NEW.roster_generation OR
     scheduling_gen<>NEW.scheduling_generation OR
     attendance_gen<>NEW.attendance_generation THEN
    RAISE EXCEPTION 'Core native Academic claim requires four frozen writer generations'
      USING ERRCODE='55000';
  END IF;
  SELECT COUNT(*) INTO class_count FROM classes
    WHERE class_id=NEW.class_id AND status IN ('READY','RUNNING');
  IF class_count<>1 THEN
    RAISE EXCEPTION 'Core native Academic claim requires an eligible live class'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_iig_guard_native_academic_claim
  BEFORE INSERT OR UPDATE OR DELETE ON academic_native_command_claims
  FOR EACH ROW EXECUTE FUNCTION iig_guard_native_academic_claim();

CREATE OR REPLACE FUNCTION iig_audit_native_academic_claim()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO academic_native_command_claim_audit
    (actor_username,operation,request_key,request_hash,class_id,
     event_kind,event_id,outbox_hash)
  VALUES
    (NEW.actor_username,NEW.operation,NEW.request_key,NEW.request_hash,
     NEW.class_id,
     CASE WHEN TG_OP='INSERT' THEN 'ACQUIRED' ELSE 'RELEASED' END,
     NEW.event_id,NEW.outbox_hash);
  RETURN NULL;
END;
$$;

CREATE TRIGGER trg_iig_audit_native_academic_claim
  AFTER INSERT OR UPDATE ON academic_native_command_claims
  FOR EACH ROW EXECUTE FUNCTION iig_audit_native_academic_claim();

CREATE OR REPLACE FUNCTION iig_reject_native_academic_claim_tampering()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Native Academic claim history is immutable'
    USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER trg_iig_native_claim_audit_no_mutation
  BEFORE UPDATE OR DELETE ON academic_native_command_claim_audit
  FOR EACH ROW EXECUTE FUNCTION iig_reject_native_academic_claim_tampering();
CREATE TRIGGER trg_iig_native_claim_no_truncate
  BEFORE TRUNCATE ON academic_native_command_claims
  FOR EACH STATEMENT EXECUTE FUNCTION iig_reject_native_academic_claim_tampering();
CREATE TRIGGER trg_iig_native_claim_audit_no_truncate
  BEFORE TRUNCATE ON academic_native_command_claim_audit
  FOR EACH STATEMENT EXECUTE FUNCTION iig_reject_native_academic_claim_tampering();

-- Retain the candidate handback barrier while extending it to independent
-- native timetables. PostgreSQL holds the writer control UPDATE lock before
-- these reads: no concurrent admitted native command can slip past handback.
CREATE OR REPLACE FUNCTION iig_guard_academic_candidate_claim_handback()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' AND
     (EXISTS(SELECT 1 FROM academic_candidate_claims WHERE state='OPEN') OR
      EXISTS(SELECT 1 FROM academic_native_command_claims WHERE state='OPEN')) THEN
    RAISE EXCEPTION 'Unreleased Academic candidate or native command claim prevents Core handback'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION iig_block_roster_native_claim_handback()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.writer_mode='QUIESCED' AND NEW.writer_mode='CORE' AND
     EXISTS(SELECT 1 FROM academic_native_command_claims WHERE state='OPEN') THEN
    RAISE EXCEPTION 'Unreleased native Academic command claim prevents roster handback'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_iig_block_roster_native_claim_handback
  BEFORE UPDATE OF writer_mode ON academic_roster_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_block_roster_native_claim_handback();

COMMIT;

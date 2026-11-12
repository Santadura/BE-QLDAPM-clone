-- Phase 8t: transactionally fence EVERY Core student, membership, target,
-- result mutation. CORE is the only legal writer; QUIESCED is a safe hold,
-- NOT permission for Academic to publish authoritative changes.
-- The FOR SHARE statement trigger serializes Core DML with the control UPDATE:
-- a freeze waits for already admitted transactions and blocks later writes.
BEGIN;

CREATE TABLE academic_roster_writer_control (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton=TRUE),
  writer_mode VARCHAR(16) NOT NULL DEFAULT 'CORE'
    CHECK (writer_mode IN ('CORE','QUIESCED')),
  generation BIGINT NOT NULL DEFAULT 1 CHECK (generation>0),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO academic_roster_writer_control(singleton,writer_mode)
VALUES(TRUE,'CORE');

CREATE TABLE academic_roster_writer_audit (
  audit_id BIGSERIAL PRIMARY KEY,
  generation BIGINT NOT NULL UNIQUE CHECK (generation>1),
  from_mode VARCHAR(16) NOT NULL CHECK (from_mode IN ('CORE','QUIESCED')),
  to_mode VARCHAR(16) NOT NULL CHECK (to_mode IN ('CORE','QUIESCED')),
  actor VARCHAR(100) NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (from_mode<>to_mode)
);

CREATE OR REPLACE FUNCTION iig_guard_academic_roster_writer_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  actor_value TEXT;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Academic roster writer control cannot be deleted'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='INSERT' THEN
    RAISE EXCEPTION 'Academic roster writer control cannot be inserted'
      USING ERRCODE='55000';
  END IF;
  IF NEW.singleton IS DISTINCT FROM OLD.singleton OR
     NEW.generation IS DISTINCT FROM OLD.generation OR
     NEW.changed_at IS DISTINCT FROM OLD.changed_at THEN
    RAISE EXCEPTION 'Academic roster generation is database-managed'
      USING ERRCODE='55000';
  END IF;
  IF NEW.writer_mode IS NOT DISTINCT FROM OLD.writer_mode THEN
    RETURN OLD;
  END IF;
  -- The control row is already UPDATE-locked by PostgreSQL. This explicit
  -- monotonically increasing generation makes CORE->QUIESCED->CORE visible
  -- even when a cutover proof sees CORE at both endpoints (ABA).
  NEW.generation:=OLD.generation+1;
  NEW.changed_at:=NOW();
  actor_value:=NULLIF(current_setting('iig.academic_roster_operator',true),'');
  INSERT INTO academic_roster_writer_audit
    (generation,from_mode,to_mode,actor)
  VALUES(NEW.generation,OLD.writer_mode,NEW.writer_mode,
    LEFT(COALESCE(actor_value,'SQL_OPERATOR'),100));
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_iig_academic_roster_writer_update
  BEFORE UPDATE OR INSERT OR DELETE ON academic_roster_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_roster_writer_transition();

CREATE OR REPLACE FUNCTION iig_require_core_academic_roster_writer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  current_mode VARCHAR(16);
BEGIN
  IF TG_OP='TRUNCATE' THEN
    -- Truncate produces no per-row source events and is never safe, even
    -- while CORE owns writes. Force ordinary DELETE/tombstone events.
    RAISE EXCEPTION 'Academic roster source TRUNCATE forbidden'
      USING ERRCODE='55000';
  END IF;
  SELECT writer_mode INTO STRICT current_mode
  FROM academic_roster_writer_control WHERE singleton=TRUE FOR SHARE;
  IF current_mode<>'CORE' THEN
    RAISE EXCEPTION 'Core Academic roster writer is QUIESCED'
      USING ERRCODE='55000';
  END IF;
  RETURN NULL; -- BEFORE STATEMENT triggers ignore the return value.
END;
$$;

-- Statement-level rather than row-level guarantees even updates/deletes
-- matching zero rows cannot silently pass during QUIESCED.
CREATE TRIGGER trg_iig_require_core_student_writer
  BEFORE INSERT OR UPDATE OR DELETE ON students
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_require_core_membership_writer
  BEFORE INSERT OR UPDATE OR DELETE ON class_students
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_require_core_target_writer
  BEFORE INSERT OR UPDATE OR DELETE ON student_targets
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_require_core_result_writer
  BEFORE INSERT OR UPDATE OR DELETE ON student_results
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();

CREATE TRIGGER trg_iig_student_no_truncate
  BEFORE TRUNCATE ON students
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_membership_no_truncate
  BEFORE TRUNCATE ON class_students
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_target_no_truncate
  BEFORE TRUNCATE ON student_targets
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();
CREATE TRIGGER trg_iig_result_no_truncate
  BEFORE TRUNCATE ON student_results
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_core_academic_roster_writer();

CREATE OR REPLACE FUNCTION iig_require_immutable_roster_writer_audit()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic roster writer audit is immutable'
    USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER trg_iig_roster_writer_audit_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_writer_audit
  FOR EACH ROW EXECUTE FUNCTION iig_require_immutable_roster_writer_audit();
CREATE TRIGGER trg_iig_roster_writer_audit_no_truncate
  BEFORE TRUNCATE ON academic_roster_writer_audit
  FOR EACH STATEMENT EXECUTE FUNCTION iig_require_immutable_roster_writer_audit();

COMMIT;

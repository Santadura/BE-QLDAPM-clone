-- Academic leases cannot survive a Core Scheduling writer-mode ABA.
-- The generation clock was introduced in 8f-6. An old lease with
-- generation zero is invalid until explicitly re-reserved and reviewed.
ALTER TABLE scheduling_academic_session_leases
  ADD COLUMN IF NOT EXISTS expected_writer_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE scheduling_academic_session_leases
  ADD CONSTRAINT scheduling_academic_lease_generation_nonnegative
  CHECK(expected_writer_generation>=0);

-- Do not permit a lease to be manufactured for the wrong generation or
-- while Core remains an active assignment writer, even via direct SQL.
CREATE OR REPLACE FUNCTION iig_fence_academic_lease_generation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE writer_mode_now VARCHAR(20);
DECLARE generation_now BIGINT;
BEGIN
  SELECT writer_mode,writer_generation
    INTO STRICT writer_mode_now,generation_now
    FROM scheduling_assignment_writer_control WHERE singleton=TRUE FOR SHARE;
  IF writer_mode_now<>'QUIESCED' OR
     NEW.expected_writer_generation<>generation_now THEN
    RAISE EXCEPTION 'Academic lease writer generation is no longer current'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_fence_academic_lease_generation
  ON scheduling_academic_session_leases;
CREATE TRIGGER trg_iig_fence_academic_lease_generation
  BEFORE INSERT OR UPDATE OF expected_writer_generation
  ON scheduling_academic_session_leases
  FOR EACH ROW EXECUTE FUNCTION iig_fence_academic_lease_generation();

-- A mode transition is prohibited while any unexpired Academic lease
-- remains, including moving from QUIESCED into CORE. Once the last lease
-- releases/expires, generation changes cannot silently reuse old evidence.

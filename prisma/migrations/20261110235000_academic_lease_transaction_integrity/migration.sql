-- Phase 8g-3: generation-stamped Core Academic leases are immutable
-- domain evidence, not writable metadata. A Scheduling command may update
-- ONLY the bounded expiry, and only while writer/class/session are live.
-- The trigger also fences manual SQL and all alternate HTTP paths.
CREATE OR REPLACE FUNCTION iig_guard_academic_lease_transaction_integrity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mode_now VARCHAR(20);
DECLARE gen_now BIGINT;
BEGIN
  SELECT writer_mode,writer_generation
    INTO STRICT mode_now,gen_now
    FROM scheduling_assignment_writer_control WHERE singleton=TRUE FOR SHARE;
  IF mode_now<>'QUIESCED' OR gen_now<>NEW.expected_writer_generation THEN
    RAISE EXCEPTION 'Academic lease is outside its Scheduling writer generation'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF OLD.expires_at<=clock_timestamp() OR
       ROW(NEW.lease_id,NEW.class_id,NEW.class_session_id,
         NEW.expected_class_version,NEW.expected_session_version,
         NEW.expected_writer_generation,NEW.command_key,NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.lease_id,OLD.class_id,OLD.class_session_id,
         OLD.expected_class_version,OLD.expected_session_version,
         OLD.expected_writer_generation,OLD.command_key,OLD.created_at) THEN
      RAISE EXCEPTION 'Expired or immutable Academic lease identity cannot change'
        USING ERRCODE='55000';
    END IF;
  END IF;
  IF NEW.expires_at<=clock_timestamp() OR
      NEW.expires_at>clock_timestamp()+interval '5 minutes 30 seconds' THEN
    RAISE EXCEPTION 'Academic lease expiry must remain bounded'
      USING ERRCODE='55000';
  END IF;
  -- Include the status and versions in the SQL guard. A foreign key alone
  -- cannot prove that the current session is still assignable.
  PERFORM 1 FROM classes c
    JOIN class_sessions s ON s.class_id=c.class_id
    WHERE c.class_id=NEW.class_id AND
      s.class_session_id=NEW.class_session_id AND
      c.scheduling_version=NEW.expected_class_version AND
      s.scheduling_version=NEW.expected_session_version AND
      c.status IN ('READY','RUNNING') AND s.status='PLANNED' AND
      s.date>=c.start_date AND s.date<=c.end_date
    FOR SHARE OF c,s;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Academic lease class/session evidence is stale'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_academic_lease_transaction_integrity
  ON scheduling_academic_session_leases;
CREATE TRIGGER trg_iig_academic_lease_transaction_integrity
  BEFORE INSERT OR UPDATE ON scheduling_academic_session_leases
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_lease_transaction_integrity();

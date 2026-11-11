-- Phase 8i: durable, immutable Core class-session change feed.
-- This is a PRE-CUTOVER source journal. Core is still the only domain writer.
-- All previously committed lessons are backfilled under one table lock.
BEGIN;
CREATE TABLE IF NOT EXISTS academic_session_source_outbox (
  event_id BIGSERIAL PRIMARY KEY,
  class_session_id VARCHAR(50) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  delivered_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS academic_session_outbox_pending
  ON academic_session_source_outbox(event_id) WHERE delivered_at IS NULL;
CREATE INDEX IF NOT EXISTS academic_session_outbox_session
  ON academic_session_source_outbox(class_session_id,event_id DESC);

LOCK TABLE class_sessions IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO academic_session_source_outbox
  (class_session_id,class_id,event_kind,source_revision,payload)
SELECT s.class_session_id,s.class_id,'UPSERT',r.revision,to_jsonb(s)
FROM class_sessions s CROSS JOIN academic_session_revision r
WHERE r.singleton=TRUE ORDER BY s.class_session_id;

-- Reuse the EXISTING transactionally serialized global source clock.
-- A row-level event receives its ID only AFTER acquiring the clock row lock.
-- Therefore a later committed event cannot sneak behind a history cursor.
CREATE OR REPLACE FUNCTION iig_bump_academic_session_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE next_revision BIGINT;
BEGIN
  UPDATE academic_session_revision
    SET revision=revision+1,updated_at=NOW()
    WHERE singleton=TRUE RETURNING revision INTO next_revision;
  IF next_revision IS NULL THEN
    RAISE EXCEPTION 'Academic session source clock missing'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='DELETE' THEN
    INSERT INTO academic_session_source_outbox
      (class_session_id,class_id,event_kind,source_revision,payload)
    VALUES(OLD.class_session_id,OLD.class_id,'DELETE',next_revision,to_jsonb(OLD));
    RETURN OLD;
  END IF;
  INSERT INTO academic_session_source_outbox
    (class_session_id,class_id,event_kind,source_revision,payload)
  VALUES(NEW.class_session_id,NEW.class_id,'UPSERT',next_revision,to_jsonb(NEW));
  RETURN NEW;
END;
$$;

-- Event identity/contents are permanent. Only delivery NULL->timestamp
-- can change. The source clock/triggers live in the SAME Core transaction.
CREATE OR REPLACE FUNCTION iig_guard_academic_session_source_event()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Academic source history is immutable' USING ERRCODE='55000';
  END IF;
  IF NEW.event_id IS DISTINCT FROM OLD.event_id OR
     NEW.class_session_id IS DISTINCT FROM OLD.class_session_id OR
     NEW.class_id IS DISTINCT FROM OLD.class_id OR
     NEW.event_kind IS DISTINCT FROM OLD.event_kind OR
     NEW.source_revision IS DISTINCT FROM OLD.source_revision OR
     NEW.payload IS DISTINCT FROM OLD.payload OR
     NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR
     (OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at)
  THEN
    RAISE EXCEPTION 'Academic source event cannot be rewritten'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_academic_source_event_guard
  ON academic_session_source_outbox;
CREATE TRIGGER trg_iig_academic_source_event_guard
  BEFORE UPDATE OR DELETE ON academic_session_source_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_guard_academic_session_source_event();
COMMIT;

-- Academic planned-session read freshness belongs to a separate transactional
-- clock. Increment it in the SAME Core transaction as any session insert,
-- edit, cancellation or deletion, including reschedule and cascaded deletes.
-- The Academic PostgreSQL may then keep independently persisted JSON without
-- treating an arbitrary cache TTL as proof of data correctness.
CREATE TABLE IF NOT EXISTS academic_session_revision (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton=TRUE),
  revision BIGINT NOT NULL DEFAULT 1 CHECK (revision>=1),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO academic_session_revision(singleton,revision) VALUES(TRUE,1)
  ON CONFLICT(singleton) DO NOTHING;

CREATE OR REPLACE FUNCTION iig_bump_academic_session_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  UPDATE academic_session_revision
     SET revision=revision+1,updated_at=NOW()
   WHERE singleton=TRUE;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_academic_session_revision ON class_sessions;
CREATE TRIGGER trg_iig_academic_session_revision
  AFTER INSERT OR UPDATE OR DELETE ON class_sessions
  FOR EACH ROW EXECUTE FUNCTION iig_bump_academic_session_revision();

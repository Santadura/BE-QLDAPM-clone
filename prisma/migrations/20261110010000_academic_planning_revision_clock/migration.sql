-- Core writes still own class and course changes. Academic GETs may read a
-- separately persisted snapshot if Core's cheap monotonic clock proves that
-- their copy is current. The clock advances in the same Core transaction as
-- each relevant class mutation, so it never acknowledges uncommitted changes.
CREATE TABLE IF NOT EXISTS academic_planning_revision (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  revision BIGINT NOT NULL DEFAULT 1 CHECK(revision>=1),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO academic_planning_revision(singleton,revision) VALUES(TRUE,1)
ON CONFLICT(singleton) DO NOTHING;

CREATE OR REPLACE FUNCTION iig_bump_academic_planning_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  UPDATE academic_planning_revision
     SET revision=revision+1,updated_at=NOW() WHERE singleton=TRUE;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_academic_planning_classes ON classes;
CREATE TRIGGER trg_iig_academic_planning_classes
  AFTER INSERT OR UPDATE OR DELETE ON classes
  FOR EACH ROW EXECUTE FUNCTION iig_bump_academic_planning_revision();
DROP TRIGGER IF EXISTS trg_iig_academic_planning_courses ON courses;
CREATE TRIGGER trg_iig_academic_planning_courses
  AFTER UPDATE OF name ON courses
  FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name)
  EXECUTE FUNCTION iig_bump_academic_planning_revision();

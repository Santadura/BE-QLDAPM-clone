-- Student + class membership history for an independent Academic read model.
-- Core is still the ONLY public writer. Bootstrap is protected from concurrent
-- changes; a transactionally locked clock makes IDs commit-ordered.
BEGIN;
CREATE TABLE academic_roster_source_clock (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton=TRUE),
  revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0)
);
INSERT INTO academic_roster_source_clock(singleton,revision) VALUES(TRUE,0);

CREATE TABLE academic_roster_source_outbox (
  event_id BIGSERIAL PRIMARY KEY,
  entity_type VARCHAR(20) NOT NULL CHECK(entity_type IN ('STUDENT','MEMBERSHIP')),
  entity_id VARCHAR(50) NOT NULL,
  event_kind VARCHAR(10) NOT NULL CHECK(event_kind IN ('UPSERT','DELETE')),
  source_revision BIGINT NOT NULL CHECK(source_revision>=1),
  payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(source_revision)
);
CREATE INDEX academic_roster_source_by_entity
  ON academic_roster_source_outbox(entity_type,entity_id,event_id DESC);

-- LOCK covers the exact window in which the backfill and triggers are
-- installed; no UPDATE can escape the source event stream.
LOCK TABLE students, class_students IN SHARE ROW EXCLUSIVE MODE;
WITH originals AS (
  SELECT 'STUDENT'::VARCHAR(20) AS entity_type,student_id AS entity_id,
         to_jsonb(s) AS payload FROM students s
  UNION ALL
  SELECT 'MEMBERSHIP'::VARCHAR(20),class_student_id,to_jsonb(m)
  FROM class_students m
), numbered AS (
  SELECT entity_type,entity_id,payload,
    row_number() OVER (ORDER BY entity_type,entity_id) AS source_revision
  FROM originals
)
INSERT INTO academic_roster_source_outbox
  (entity_type,entity_id,event_kind,source_revision,payload)
SELECT entity_type,entity_id,'UPSERT',source_revision,payload
FROM numbered ORDER BY source_revision;

UPDATE academic_roster_source_clock
  SET revision=(SELECT COALESCE(MAX(source_revision),0)
                FROM academic_roster_source_outbox) WHERE singleton=TRUE;

CREATE OR REPLACE FUNCTION iig_record_academic_roster_source()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  next_revision BIGINT;
  row_payload JSONB;
  entity_type_value VARCHAR(20);
  entity_id_value VARCHAR(50);
BEGIN
  -- Serializes all student/roster writers before allocating a sequence id.
  UPDATE academic_roster_source_clock SET revision=revision+1
    WHERE singleton=TRUE RETURNING revision INTO next_revision;
  IF next_revision IS NULL THEN
    RAISE EXCEPTION 'Academic roster source clock missing' USING ERRCODE='55000';
  END IF;
  row_payload:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  IF TG_TABLE_NAME='students' THEN
    entity_type_value:='STUDENT';
    entity_id_value:=row_payload->>'student_id';
  ELSIF TG_TABLE_NAME='class_students' THEN
    entity_type_value:='MEMBERSHIP';
    entity_id_value:=row_payload->>'class_student_id';
  ELSE
    RAISE EXCEPTION 'Unknown academic roster source table' USING ERRCODE='55000';
  END IF;
  INSERT INTO academic_roster_source_outbox
    (entity_type,entity_id,event_kind,source_revision,payload)
  VALUES(entity_type_value,entity_id_value,
         CASE WHEN TG_OP='DELETE' THEN 'DELETE' ELSE 'UPSERT' END,
         next_revision,row_payload);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_iig_student_roster_source
  AFTER INSERT OR UPDATE OR DELETE ON students
  FOR EACH ROW EXECUTE FUNCTION iig_record_academic_roster_source();
CREATE TRIGGER trg_iig_class_student_roster_source
  AFTER INSERT OR UPDATE OR DELETE ON class_students
  FOR EACH ROW EXECUTE FUNCTION iig_record_academic_roster_source();

CREATE OR REPLACE FUNCTION iig_guard_roster_source_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Academic student roster source history is immutable'
    USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER trg_iig_roster_source_immutable
  BEFORE UPDATE OR DELETE ON academic_roster_source_outbox
  FOR EACH ROW EXECUTE FUNCTION iig_guard_roster_source_history();
CREATE TRIGGER trg_iig_roster_source_no_truncate
  BEFORE TRUNCATE ON academic_roster_source_outbox
  FOR EACH STATEMENT EXECUTE FUNCTION iig_guard_roster_source_history();
COMMIT;

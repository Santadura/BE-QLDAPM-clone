-- Academic extraction: transactionally capture Student Targets and Results.
-- Core remains the ONLY writer; this adds immutable history and makes no
-- public read/write route change.
BEGIN;

ALTER TABLE academic_roster_source_outbox
  DROP CONSTRAINT academic_roster_source_outbox_entity_type_check;
ALTER TABLE academic_roster_source_outbox
  ADD CONSTRAINT academic_roster_source_outbox_entity_type_check
  CHECK (entity_type IN ('STUDENT','MEMBERSHIP','TARGET','RESULT'));

-- Prevent gaps between the new backfill and creation of row triggers.
LOCK TABLE student_targets, student_results IN SHARE ROW EXCLUSIVE MODE;

-- Take the SAME lock as the existing student/membership trigger. Allocated
-- revisions are transaction-serialized; no concurrent writer can overtake
-- the historical cursor while targets/results are initially populated.
DO $$
DECLARE baseline BIGINT;
BEGIN
  SELECT revision INTO baseline FROM academic_roster_source_clock
    WHERE singleton=TRUE FOR UPDATE;
  IF baseline IS NULL THEN
    RAISE EXCEPTION 'Core Academic roster source clock missing'
      USING ERRCODE='55000';
  END IF;
  WITH originals AS (
    SELECT 'TARGET'::VARCHAR(20) entity_type,
      student_target_id entity_id,to_jsonb(t) payload FROM student_targets t
    UNION ALL
    SELECT 'RESULT'::VARCHAR(20),
      student_result_id,to_jsonb(r) FROM student_results r
  ), numbered AS (
    SELECT entity_type,entity_id,payload,
      ROW_NUMBER() OVER(ORDER BY entity_type,entity_id) rn FROM originals
  )
  INSERT INTO academic_roster_source_outbox
    (entity_type,entity_id,event_kind,source_revision,payload)
  SELECT entity_type,entity_id,'UPSERT',baseline+rn,payload
  FROM numbered ORDER BY rn;

  UPDATE academic_roster_source_clock
  SET revision=(SELECT COALESCE(MAX(source_revision),baseline)
                FROM academic_roster_source_outbox)
  WHERE singleton=TRUE;
END;
$$;

-- Replace trigger function WITHOUT changing existing two entity kinds.
CREATE OR REPLACE FUNCTION iig_record_academic_roster_source()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  next_revision BIGINT;
  row_payload JSONB;
  entity_type_value VARCHAR(20);
  entity_id_value VARCHAR(50);
BEGIN
  UPDATE academic_roster_source_clock SET revision=revision+1
    WHERE singleton=TRUE RETURNING revision INTO next_revision;
  IF next_revision IS NULL THEN
    RAISE EXCEPTION 'Academic roster source clock missing' USING ERRCODE='55000';
  END IF;
  row_payload:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
  CASE TG_TABLE_NAME
    WHEN 'students' THEN
      entity_type_value:='STUDENT';
      entity_id_value:=row_payload->>'student_id';
    WHEN 'class_students' THEN
      entity_type_value:='MEMBERSHIP';
      entity_id_value:=row_payload->>'class_student_id';
    WHEN 'student_targets' THEN
      entity_type_value:='TARGET';
      entity_id_value:=row_payload->>'student_target_id';
    WHEN 'student_results' THEN
      entity_type_value:='RESULT';
      entity_id_value:=row_payload->>'student_result_id';
    ELSE
      RAISE EXCEPTION 'Unknown Academic roster source table'
        USING ERRCODE='55000';
  END CASE;
  INSERT INTO academic_roster_source_outbox
    (entity_type,entity_id,event_kind,source_revision,payload)
  VALUES(entity_type_value,entity_id_value,
    CASE WHEN TG_OP='DELETE' THEN 'DELETE' ELSE 'UPSERT' END,
    next_revision,row_payload);
  RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE TRIGGER trg_iig_student_target_source
  AFTER INSERT OR UPDATE OR DELETE ON student_targets
  FOR EACH ROW EXECUTE FUNCTION iig_record_academic_roster_source();
CREATE TRIGGER trg_iig_student_result_source
  AFTER INSERT OR UPDATE OR DELETE ON student_results
  FOR EACH ROW EXECUTE FUNCTION iig_record_academic_roster_source();
COMMIT;

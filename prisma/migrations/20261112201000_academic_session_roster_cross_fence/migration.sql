-- A Core timetable transaction must not start after the student/roster
-- writer has been frozen, even if the independent HTTP ingress checked the
-- previous CORE state just before the operator transition.
-- Take a row-level PostgreSQL FOR SHARE lock for the entire writer tx so a
-- concurrent roster CORE -> QUIESCED UPDATE waits for the admitted DML.
-- Both ownership controls are still CORE/QUIESCED-only in this phase.
BEGIN;

CREATE OR REPLACE FUNCTION iig_require_core_academic_writer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE roster_mode VARCHAR(16);
BEGIN
  PERFORM 1 FROM academic_session_writer_control
    WHERE singleton=TRUE AND writer_mode='CORE' FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Core Academic timetable writer is QUIESCED'
      USING ERRCODE='55000';
  END IF;

  SELECT writer_mode INTO STRICT roster_mode
    FROM academic_roster_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF roster_mode<>'CORE' THEN
    RAISE EXCEPTION 'Core Academic timetable writer blocked by frozen roster'
      USING ERRCODE='55000';
  END IF;

  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

COMMIT;

-- Complete the symmetric Academic/Roster barrier in Core.
-- A frozen timetable must stop EVERY student/membership/target/result DML,
-- not merely HTTP planning commands. Order locks as session -> roster,
-- matching iig_require_core_academic_writer, so concurrent transaction
-- admission and ownership freezes serialize at PostgreSQL, not at HTTP.
-- A frozen roster still has its previous, stable error contract.
BEGIN;

CREATE OR REPLACE FUNCTION iig_require_core_academic_roster_writer()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE session_mode VARCHAR(16);
DECLARE roster_mode VARCHAR(16);
BEGIN
  IF TG_OP='TRUNCATE' THEN
    RAISE EXCEPTION 'Academic roster source TRUNCATE forbidden'
      USING ERRCODE='55000';
  END IF;

  SELECT writer_mode INTO STRICT session_mode
    FROM academic_session_writer_control
    WHERE singleton=TRUE FOR SHARE;
  SELECT writer_mode INTO STRICT roster_mode
    FROM academic_roster_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF roster_mode<>'CORE' THEN
    RAISE EXCEPTION 'Core Academic roster writer is QUIESCED'
      USING ERRCODE='55000';
  END IF;
  IF session_mode<>'CORE' THEN
    RAISE EXCEPTION 'Core Academic roster write blocked by frozen sessions'
      USING ERRCODE='55000';
  END IF;
  RETURN NULL;
END;
$$;

COMMIT;

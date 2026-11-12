-- Preserve the existing Scheduling PostgreSQL authority fence for ALL
-- teaching and staff assignment writes. The previous Core-only function
-- also fenced teacher_session_attendance; that would accidentally reject
-- a verified native Attendance -> Core projection while Scheduling is frozen.
--
-- This extremely narrow exception is NOT Core business write ownership:
-- both private GUCs must name the exact still-OPEN Core claim; only the
-- admitted teaching_schedule_id can be projected, only while the independent
-- ATTENDANCE writer holds its audited generation, and only in the original
-- Core reference controller transaction. All ordinary SQL/HTTP writers,
-- DELETEs, TRUNCATEs and arbitrary assignment writes remain prohibited.
CREATE OR REPLACE FUNCTION iig_fence_core_assignment_writes()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  scheduling_mode VARCHAR(20);
  attendance_mode VARCHAR(16);
  claim_text TEXT;
  allowed_claims BIGINT;
BEGIN
  SELECT writer_mode INTO STRICT scheduling_mode
    FROM scheduling_assignment_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF scheduling_mode='CORE' THEN
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;

  IF scheduling_mode='QUIESCED'
     AND TG_TABLE_NAME='teacher_session_attendance'
     AND TG_OP IN('INSERT','UPDATE')
     AND current_setting('iig.attendance_reference_projection',TRUE)
           IS NOT DISTINCT FROM 'true'
  THEN
    SELECT writer_mode INTO STRICT attendance_mode
      FROM teacher_attendance_writer_control
      WHERE singleton=TRUE FOR SHARE;
    claim_text:=NULLIF(
      current_setting('iig.attendance_reference_claim',TRUE),'');
    IF attendance_mode='ATTENDANCE' AND claim_text IS NOT NULL THEN
      SELECT COUNT(*) INTO allowed_claims
        FROM teacher_attendance_native_claims c
        JOIN teacher_attendance_writer_control w ON w.singleton=TRUE
        WHERE c.claim_id::text=claim_text
          AND c.state='OPEN'
          AND c.teaching_schedule_id=NEW.teaching_schedule_id
          AND c.writer_generation=w.generation
          AND w.writer_mode='ATTENDANCE';
      IF allowed_claims=1 THEN RETURN NEW; END IF;
    END IF;
  END IF;

  RAISE EXCEPTION 'Core assignment writes are quiesced for ownership transfer'
    USING ERRCODE='55000';
END;
$$;

-- No trigger is removed or disabled. Existing row-level triggers continue
-- calling this upgraded function, plus the independent Attendance ownership
-- guard for the same teacher_session_attendance transaction.

-- Preserve BOTH independently authorized private reference lanes after the
-- Native Attendance handoff. The preceding Attendance migration narrowed
-- iig_fence_core_assignment_writes() but accidentally removed the original
-- Scheduling TEACHING/STAFF reference exception. The original Core writer
-- remains fenced while Scheduling is QUIESCED.
--
-- iig.assignment_reference_projection is set transaction-locally only by
-- the Scheduling private reference receivers AFTER their event, writer claim,
-- role and Academic lease checks. This function does not authorize a public
-- business writer or any unrelated Core table. Attendance's exception is
-- separate and requires the EXACT still-OPEN native Core claim.
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

  IF scheduling_mode='QUIESCED' THEN
    -- Maintain the existing versioned TEACHING and STAFF private Core
    -- projection path. Its callers must establish exact Core writer claim,
    -- immutable event digest, Academic lease and audit independently.
    IF TG_TABLE_NAME IN ('teaching_schedules','staff_schedules')
       AND current_setting('iig.assignment_reference_projection',TRUE)
             IS NOT DISTINCT FROM 'true'
    THEN
      IF TG_OP='DELETE' THEN RETURN OLD; END IF;
      RETURN NEW;
    END IF;

    -- Unrelated Core business Attendance is NEVER allowed during freeze.
    -- Only the native Attendance reference for the exact admitted
    -- teaching_schedule_id, writer generation and open claim may project.
    IF TG_TABLE_NAME='teacher_session_attendance'
       AND TG_OP IN ('INSERT','UPDATE')
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
  END IF;
  RAISE EXCEPTION
    'Core business writer is not authoritative during Scheduling handoff'
    USING ERRCODE='55000';
END;
$$;

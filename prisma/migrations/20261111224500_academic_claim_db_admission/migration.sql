-- Phase 8s-2: Core Academic candidate claim admission is a SQL-level
-- invariant, not merely an HTTP controller promise. Direct INSERT during
-- active Core writes, with outdated generations, or without a complete
-- acknowledged current UPSERT source event is rejected.
CREATE OR REPLACE FUNCTION iig_require_frozen_academic_candidate_claim()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
 a_mode VARCHAR(20);
 t_mode VARCHAR(20);
 s_mode VARCHAR(20);
 a_gen BIGINT;
 t_gen BIGINT;
 s_gen BIGINT;
 matched BIGINT;
BEGIN
 -- Lock in the same mode-order as Core's private Academic claim bridge.
 -- The Academic row lock serializes admission with CORE handback.
 SELECT writer_mode,generation INTO STRICT a_mode,a_gen
   FROM academic_session_writer_control WHERE singleton=TRUE FOR SHARE;
 SELECT writer_mode,writer_generation INTO STRICT s_mode,s_gen
   FROM scheduling_assignment_writer_control WHERE singleton=TRUE FOR SHARE;
 SELECT writer_mode,generation INTO STRICT t_mode,t_gen
   FROM teacher_attendance_writer_control WHERE singleton=TRUE FOR SHARE;
 IF a_mode<>'QUIESCED' OR s_mode<>'QUIESCED' OR t_mode<>'QUIESCED'
    OR NEW.academic_generation<>a_gen
    OR NEW.scheduling_generation<>s_gen
    OR NEW.attendance_generation<>t_gen THEN
    RAISE EXCEPTION 'Academic Core claim requires exact frozen writer generations'
      USING ERRCODE='55000';
 END IF;

 SELECT COUNT(*) INTO matched
 FROM academic_session_source_outbox e
 JOIN class_sessions c ON c.class_session_id=e.class_session_id
 WHERE e.event_id=NEW.source_event_id
   AND e.class_session_id=NEW.class_session_id AND e.class_id=NEW.class_id
   AND e.source_revision=NEW.source_revision AND e.event_kind='UPSERT'
   AND e.delivered_at IS NOT NULL
   AND e.payload IS NOT DISTINCT FROM to_jsonb(c);
 IF matched<>1 THEN
    RAISE EXCEPTION 'Academic Core claim lacks exact acknowledged live event'
      USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_require_frozen_academic_candidate_claim
 ON academic_candidate_claims;
CREATE TRIGGER trg_iig_require_frozen_academic_candidate_claim
 BEFORE INSERT ON academic_candidate_claims
 FOR EACH ROW EXECUTE FUNCTION iig_require_frozen_academic_candidate_claim();

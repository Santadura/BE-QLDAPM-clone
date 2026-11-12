-- Native Attendance writer ownership is an EXPLICIT, audit-fenced
-- transition. It is disabled by default and is NOT activated by a migration.
-- The independent writer can hold an exclusive admission while Core is an
-- idempotent reference projection, never a competing business authority.

ALTER TABLE teacher_attendance_writer_control
  ADD COLUMN IF NOT EXISTS ever_native BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE teacher_attendance_writer_control
  DROP CONSTRAINT IF EXISTS teacher_attendance_writer_control_writer_mode_check;
ALTER TABLE teacher_attendance_writer_control
  ADD CONSTRAINT teacher_attendance_writer_control_writer_mode_check
  CHECK(writer_mode IN ('CORE','QUIESCED','ATTENDANCE'));

CREATE TABLE IF NOT EXISTS teacher_attendance_native_writer_audit (
  audit_id BIGSERIAL PRIMARY KEY,
  generation BIGINT NOT NULL UNIQUE CHECK(generation>1),
  from_mode VARCHAR(16) NOT NULL CHECK(from_mode IN('CORE','QUIESCED','ATTENDANCE')),
  to_mode VARCHAR(16) NOT NULL CHECK(to_mode IN('CORE','QUIESCED','ATTENDANCE')),
  operator_username VARCHAR(100) NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK(from_mode<>to_mode)
);

CREATE TABLE IF NOT EXISTS teacher_attendance_native_claims (
  claim_id UUID PRIMARY KEY,
  actor_username VARCHAR(100) NOT NULL,
  actor_employee_id VARCHAR(50) NOT NULL,
  class_id VARCHAR(50) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
  attendance_status VARCHAR(20) NOT NULL
    CHECK(attendance_status IN ('PRESENT','ABSENT','LATE')),
  previous_attendance_id VARCHAR(50),
  previous_status VARCHAR(20)
    CHECK(previous_status IS NULL OR previous_status IN ('PRESENT','ABSENT','LATE')),
  facts_token CHAR(64) NOT NULL CHECK(facts_token ~ '^[0-9a-f]{64}$'),
  writer_generation BIGINT NOT NULL CHECK(writer_generation>0),
  state VARCHAR(12) NOT NULL DEFAULT 'OPEN'
    CHECK(state IN ('OPEN','CONFIRMED')),
  confirmed_event UUID,
  confirmed_hash CHAR(64),
  confirmed_version BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  confirmed_at TIMESTAMPTZ,
  UNIQUE(actor_username,class_id,teaching_schedule_id,request_key),
  CONSTRAINT native_attendance_claim_finality CHECK(
    (state='OPEN' AND confirmed_event IS NULL AND confirmed_hash IS NULL
       AND confirmed_version IS NULL AND confirmed_at IS NULL) OR
    (state='CONFIRMED' AND confirmed_event IS NOT NULL
       AND confirmed_hash ~ '^[0-9a-f]{64}$' AND confirmed_version>0
       AND confirmed_at IS NOT NULL)
  )
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_attendance_native_one_open_schedule
  ON teacher_attendance_native_claims(teaching_schedule_id)
  WHERE state='OPEN';

CREATE TABLE IF NOT EXISTS teacher_attendance_native_reference_receipts (
  event_id UUID PRIMARY KEY,
  claim_id UUID NOT NULL REFERENCES teacher_attendance_native_claims(claim_id),
  actor_username VARCHAR(100) NOT NULL,
  request_key VARCHAR(128) NOT NULL,
  teaching_schedule_id VARCHAR(50) NOT NULL,
  version BIGINT NOT NULL CHECK(version>0),
  payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
  attendance_id VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK(status IN ('PRESENT','ABSENT','LATE')),
  recorded_by VARCHAR(50) NOT NULL,
  response_payload JSONB NOT NULL CHECK(jsonb_typeof(response_payload)='object'),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(teaching_schedule_id,version),
  UNIQUE(claim_id)
);

CREATE OR REPLACE FUNCTION iig_native_attendance_ownership_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  scheduling_mode VARCHAR(20);
  academic_mode VARCHAR(20);
  operator_name TEXT;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Native Attendance writer control cannot be deleted'
      USING ERRCODE='55000';
  END IF;
  IF NEW.singleton IS DISTINCT FROM OLD.singleton OR
     (NEW.ever_native IS DISTINCT FROM OLD.ever_native
      AND NOT (OLD.ever_native=FALSE AND NEW.ever_native=TRUE AND
               NEW.writer_mode='ATTENDANCE' AND OLD.writer_mode='QUIESCED')) THEN
    RAISE EXCEPTION 'Native Attendance ownership history is database managed'
      USING ERRCODE='55000';
  END IF;
  IF NEW.writer_mode IS NOT DISTINCT FROM OLD.writer_mode THEN
    IF NEW.ever_native IS DISTINCT FROM OLD.ever_native THEN
      RAISE EXCEPTION 'Native ownership marker is immutable'
        USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  -- Core -> Attendance is never direct. Enter through a fully frozen
  -- Scheduling+Academic+Attendance snapshot with external parity evidence.
  SELECT writer_mode INTO STRICT scheduling_mode
    FROM scheduling_assignment_writer_control
    WHERE singleton=TRUE FOR SHARE;
  SELECT writer_mode INTO STRICT academic_mode
    FROM academic_session_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF NEW.writer_mode='ATTENDANCE' THEN
    IF OLD.writer_mode<>'QUIESCED' OR
       scheduling_mode<>'QUIESCED' OR academic_mode<>'QUIESCED' OR
       current_setting('iig.attendance_cutover_approved',TRUE)
           IS DISTINCT FROM 'true' THEN
      RAISE EXCEPTION 'Native Attendance ownership requires audited full frozen parity'
        USING ERRCODE='55000';
    END IF;
    NEW.ever_native:=TRUE;
  ELSIF OLD.writer_mode='ATTENDANCE' THEN
    IF NEW.writer_mode<>'QUIESCED' OR
       scheduling_mode<>'QUIESCED' OR academic_mode<>'QUIESCED' OR
       current_setting('iig.attendance_handback_approved',TRUE)
           IS DISTINCT FROM 'true' OR
       EXISTS(SELECT 1 FROM teacher_attendance_native_claims
              WHERE state='OPEN') THEN
      RAISE EXCEPTION 'Unconfirmed native Attendance transaction prevents handback'
        USING ERRCODE='55000';
    END IF;
  ELSIF OLD.ever_native AND NEW.writer_mode='CORE' THEN
    -- After one native ownership epoch, even QUIESCED->CORE needs
    -- an explicit independently verified handback and zero open claims.
    IF current_setting('iig.attendance_handback_approved',TRUE)
        IS DISTINCT FROM 'true' OR
       EXISTS(SELECT 1 FROM teacher_attendance_native_claims
              WHERE state='OPEN') THEN
      RAISE EXCEPTION 'Core Attendance handback lacks immutable native evidence'
        USING ERRCODE='55000';
    END IF;
  END IF;
  operator_name:=NULLIF(current_setting('iig.attendance_writer_operator',TRUE),'');
  INSERT INTO teacher_attendance_native_writer_audit
    (generation,from_mode,to_mode,operator_username)
  VALUES(OLD.generation+1,OLD.writer_mode,NEW.writer_mode,
    LEFT(COALESCE(operator_name,'SQL_OPERATOR'),100));
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_native_attendance_ownership_transition
  ON teacher_attendance_writer_control;
CREATE TRIGGER trg_iig_native_attendance_ownership_transition
  BEFORE UPDATE OR DELETE ON teacher_attendance_writer_control
  FOR EACH ROW EXECUTE FUNCTION iig_native_attendance_ownership_transition();

CREATE OR REPLACE FUNCTION iig_native_attendance_claim_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  a_mode VARCHAR(16);
  s_mode VARCHAR(20);
  academic_mode VARCHAR(20);
  generation_now BIGINT;
  found_receipt BIGINT;
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'Native Attendance writer claim cannot be removed'
      USING ERRCODE='55000';
  END IF;
  IF TG_OP='INSERT' THEN
    SELECT writer_mode,generation INTO STRICT a_mode,generation_now
      FROM teacher_attendance_writer_control WHERE singleton=TRUE FOR SHARE;
    SELECT writer_mode INTO STRICT s_mode
      FROM scheduling_assignment_writer_control WHERE singleton=TRUE FOR SHARE;
    SELECT writer_mode INTO STRICT academic_mode
      FROM academic_session_writer_control WHERE singleton=TRUE FOR SHARE;
    IF a_mode<>'ATTENDANCE' OR s_mode<>'QUIESCED' OR
       academic_mode<>'QUIESCED' OR
       NEW.writer_generation<>generation_now THEN
      RAISE EXCEPTION 'Native Attendance claim requires exclusive frozen ownership'
        USING ERRCODE='55000';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state<>'OPEN' OR NEW.state<>'CONFIRMED' OR
     NEW.claim_id IS DISTINCT FROM OLD.claim_id OR
     NEW.actor_username IS DISTINCT FROM OLD.actor_username OR
     NEW.actor_employee_id IS DISTINCT FROM OLD.actor_employee_id OR
     NEW.class_id IS DISTINCT FROM OLD.class_id OR
     NEW.teaching_schedule_id IS DISTINCT FROM OLD.teaching_schedule_id OR
     NEW.request_key IS DISTINCT FROM OLD.request_key OR
     NEW.request_hash IS DISTINCT FROM OLD.request_hash OR
     NEW.attendance_status IS DISTINCT FROM OLD.attendance_status OR
     NEW.previous_attendance_id IS DISTINCT FROM OLD.previous_attendance_id OR
     NEW.previous_status IS DISTINCT FROM OLD.previous_status OR
     NEW.facts_token IS DISTINCT FROM OLD.facts_token OR
     NEW.writer_generation IS DISTINCT FROM OLD.writer_generation OR
     NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'Native Attendance claims are immutable after admission'
      USING ERRCODE='55000';
  END IF;
  SELECT COUNT(*) INTO found_receipt
    FROM teacher_attendance_native_reference_receipts r
    WHERE r.claim_id=NEW.claim_id AND r.event_id=NEW.confirmed_event
      AND r.payload_hash=NEW.confirmed_hash
      AND r.version=NEW.confirmed_version
      AND r.actor_username=NEW.actor_username
      AND r.request_key=NEW.request_key
      AND r.teaching_schedule_id=NEW.teaching_schedule_id;
  IF found_receipt<>1 THEN
    RAISE EXCEPTION 'Native Attendance claim requires committed reference evidence'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_native_attendance_claim_guard
  ON teacher_attendance_native_claims;
CREATE TRIGGER trg_iig_native_attendance_claim_guard
  BEFORE INSERT OR UPDATE OR DELETE ON teacher_attendance_native_claims
  FOR EACH ROW EXECUTE FUNCTION iig_native_attendance_claim_guard();

CREATE OR REPLACE FUNCTION iig_attendance_native_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Native Attendance writer evidence is immutable'
    USING ERRCODE='55000';
END;
$$;
DROP TRIGGER IF EXISTS trg_iig_attendance_native_receipt_immutable
  ON teacher_attendance_native_reference_receipts;
CREATE TRIGGER trg_iig_attendance_native_receipt_immutable
  BEFORE UPDATE OR DELETE ON teacher_attendance_native_reference_receipts
  FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_immutable();
DROP TRIGGER IF EXISTS trg_iig_attendance_native_audit_immutable
  ON teacher_attendance_native_writer_audit;
CREATE TRIGGER trg_iig_attendance_native_audit_immutable
  BEFORE UPDATE OR DELETE ON teacher_attendance_native_writer_audit
  FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_immutable();

-- TRUNCATE must not bypass the append-only writer audit or reference proof.
DO $$
DECLARE tbl TEXT;
BEGIN
  FOREACH tbl IN ARRAY ARRAY[
    'teacher_attendance_native_claims',
    'teacher_attendance_native_reference_receipts',
    'teacher_attendance_native_writer_audit',
    'teacher_attendance_writer_control'
  ] LOOP
    EXECUTE format('DROP TRIGGER IF EXISTS trg_iig_native_no_truncate ON %I',tbl);
    EXECUTE format(
      'CREATE TRIGGER trg_iig_native_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION iig_attendance_native_immutable()',tbl);
  END LOOP;
END;
$$;

-- Original business PUT must remain forbidden while an independent owner is
-- active. Only the exact Core-owned internal reference transaction is
-- permitted to project an admitted external event; regular Core writes
-- cannot use this transaction-local escape through any public endpoint.
CREATE OR REPLACE FUNCTION iig_guard_teacher_attendance_quiescence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  mode VARCHAR(16);
  admitted BIGINT;
  claim_value TEXT;
BEGIN
  IF TG_OP='TRUNCATE' THEN
    RAISE EXCEPTION 'Attendance source truncation forbidden'
      USING ERRCODE='55000';
  END IF;
  SELECT writer_mode INTO STRICT mode
    FROM teacher_attendance_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF mode<>'CORE' THEN
    IF mode<>'ATTENDANCE' OR
       current_setting('iig.attendance_reference_projection',TRUE)
            IS DISTINCT FROM 'true' THEN
      RAISE EXCEPTION 'Core Attendance business writer is not authoritative'
        USING ERRCODE='55000';
    END IF;
    claim_value:=NULLIF(current_setting('iig.attendance_reference_claim',TRUE),'');
    SELECT COUNT(*) INTO admitted
    FROM teacher_attendance_native_claims
    WHERE claim_id::text=claim_value AND state='OPEN';
    IF admitted<>1 THEN
      RAISE EXCEPTION 'Reference projection has no exact open native claim'
        USING ERRCODE='55000';
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

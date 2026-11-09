-- Phase 8d: Core reference receiver for a future Scheduling-owned writer.
-- No public mutation routing changes. The receiver is disabled by default,
-- and can apply only standalone STAFF shifts while Core is QUIESCED.
--
-- Revisions and digests are retained in Core independently so a replay after
-- a lost HTTP response cannot apply a different payload at the same version.
CREATE TABLE IF NOT EXISTS scheduling_assignment_reference_receipts (
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('TEACHING','STAFF')),
  schedule_id VARCHAR(50) NOT NULL,
  last_revision BIGINT NOT NULL CHECK (last_revision>=1),
  last_event_id VARCHAR(50) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  actor_username VARCHAR(100) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(source_type,schedule_id),
  UNIQUE(last_event_id)
);

-- When the legacy Core writer is fenced, ONLY the explicitly scoped private
-- reference-projection transaction can modify the assignment tables. A
-- transaction-local GUC is used rather than a permanent DB session setting.
-- This is NOT a secure boundary against someone with full Core DB credentials;
-- production must use separated least-privilege DB roles before enabling.
CREATE OR REPLACE FUNCTION iig_fence_core_assignment_writes()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mode VARCHAR(20);
BEGIN
  SELECT writer_mode INTO STRICT mode
    FROM scheduling_assignment_writer_control
    WHERE singleton=TRUE FOR SHARE;
  IF mode='CORE'
     OR (mode='QUIESCED'
         AND current_setting('iig.assignment_reference_projection',TRUE)='true')
  THEN
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'Core assignment writes are fenced during Scheduling handoff'
    USING ERRCODE='55000';
END;
$$;

-- Core's old assignment feed must NOT re-emit a Scheduling-originated
-- reference write as a new assignment source revision. The receiver records
-- the identical Scheduling revision separately and marks it delivered.
CREATE OR REPLACE FUNCTION iig_enqueue_assignment_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  kind VARCHAR(12);
  identifier VARCHAR(50);
BEGIN
  IF current_setting('iig.assignment_reference_projection',TRUE)='true' THEN
    RETURN NULL;
  END IF;
  IF TG_TABLE_NAME='teaching_schedules' THEN
    kind := 'TEACHING';
    IF TG_OP='DELETE' THEN
      identifier := OLD.teaching_schedule_id;
    ELSE
      identifier := NEW.teaching_schedule_id;
    END IF;
  ELSE
    kind := 'STAFF';
    IF TG_OP='DELETE' THEN
      identifier := OLD.staff_schedule_id;
    ELSE
      identifier := NEW.staff_schedule_id;
    END IF;
  END IF;
  INSERT INTO scheduling_assignment_outbox(source_type,schedule_id,version,delivered_at)
    VALUES (kind,identifier,1,NULL)
  ON CONFLICT(source_type,schedule_id) DO UPDATE SET
    version=scheduling_assignment_outbox.version+1,
    delivered_at=NULL,
    updated_at=NOW();
  RETURN NULL;
END;
$$;

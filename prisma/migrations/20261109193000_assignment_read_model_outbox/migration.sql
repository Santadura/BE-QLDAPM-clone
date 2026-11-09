-- Core remains the assignment write owner during phase 8a. Every committed
-- write (including writes from ClassSessionService / ClassStaffingRangeService)
-- invalidates the independent Scheduling read model in the SAME transaction.
CREATE TABLE IF NOT EXISTS scheduling_assignment_outbox (
  source_type VARCHAR(12) NOT NULL CHECK (source_type IN ('TEACHING','STAFF')),
  schedule_id VARCHAR(50) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1 CHECK (version>=1),
  delivered_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (source_type,schedule_id)
);
CREATE INDEX IF NOT EXISTS scheduling_assignment_pending_idx
  ON scheduling_assignment_outbox(updated_at,source_type,schedule_id)
  WHERE delivered_at IS NULL;

CREATE OR REPLACE FUNCTION iig_enqueue_assignment_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  kind VARCHAR(12);
  identifier VARCHAR(50);
BEGIN
  kind := CASE WHEN TG_TABLE_NAME='teaching_schedules' THEN 'TEACHING' ELSE 'STAFF' END;
  IF TG_OP='DELETE' THEN
    identifier := CASE WHEN kind='TEACHING'
      THEN OLD.teaching_schedule_id ELSE OLD.staff_schedule_id END;
  ELSE
    identifier := CASE WHEN kind='TEACHING'
      THEN NEW.teaching_schedule_id ELSE NEW.staff_schedule_id END;
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

DROP TRIGGER IF EXISTS trg_iig_teaching_read_model ON teaching_schedules;
CREATE TRIGGER trg_iig_teaching_read_model
  AFTER INSERT OR UPDATE OR DELETE ON teaching_schedules
  FOR EACH ROW EXECUTE FUNCTION iig_enqueue_assignment_change();
DROP TRIGGER IF EXISTS trg_iig_staff_read_model ON staff_schedules;
CREATE TRIGGER trg_iig_staff_read_model
  AFTER INSERT OR UPDATE OR DELETE ON staff_schedules
  FOR EACH ROW EXECUTE FUNCTION iig_enqueue_assignment_change();

-- Existing production records (created before the trigger) also need a
-- snapshot and durable outbox entry. Do not mutate existing versions.
INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
SELECT 'TEACHING',teaching_schedule_id FROM teaching_schedules
ON CONFLICT(source_type,schedule_id) DO NOTHING;
INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
SELECT 'STAFF',staff_schedule_id FROM staff_schedules
ON CONFLICT(source_type,schedule_id) DO NOTHING;

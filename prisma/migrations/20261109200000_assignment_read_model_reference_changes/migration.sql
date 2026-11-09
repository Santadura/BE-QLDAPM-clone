-- Assignment response shapes include employee names, class metadata and
-- staff roles. All those metadata writes must invalidate the same durable
-- outbox, otherwise Scheduling GETs would display stale person/class labels.
CREATE OR REPLACE FUNCTION iig_enqueue_assignment_reference_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME='employees' THEN
    INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
      SELECT 'TEACHING',teaching_schedule_id FROM teaching_schedules
      WHERE teacher_id=NEW.employee_id
    ON CONFLICT(source_type,schedule_id) DO UPDATE SET
      version=scheduling_assignment_outbox.version+1,delivered_at=NULL,updated_at=NOW();
    INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
      SELECT 'STAFF',staff_schedule_id FROM staff_schedules
      WHERE employee_id=NEW.employee_id
    ON CONFLICT(source_type,schedule_id) DO UPDATE SET
      version=scheduling_assignment_outbox.version+1,delivered_at=NULL,updated_at=NOW();
  ELSIF TG_TABLE_NAME='classes' THEN
    INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
      SELECT 'TEACHING',teaching_schedule_id FROM teaching_schedules
      WHERE class_id=NEW.class_id
    ON CONFLICT(source_type,schedule_id) DO UPDATE SET
      version=scheduling_assignment_outbox.version+1,delivered_at=NULL,updated_at=NOW();
    INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
      SELECT 'STAFF',staff_schedule_id FROM staff_schedules
      WHERE class_id=NEW.class_id
    ON CONFLICT(source_type,schedule_id) DO UPDATE SET
      version=scheduling_assignment_outbox.version+1,delivered_at=NULL,updated_at=NOW();
  ELSIF TG_TABLE_NAME='users' THEN
    INSERT INTO scheduling_assignment_outbox(source_type,schedule_id)
      SELECT 'STAFF',s.staff_schedule_id FROM staff_schedules s
      JOIN employees e ON e.employee_id=s.employee_id
      WHERE e.username=NEW.username
    ON CONFLICT(source_type,schedule_id) DO UPDATE SET
      version=scheduling_assignment_outbox.version+1,delivered_at=NULL,updated_at=NOW();
  END IF;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_iig_employee_assignment_reference ON employees;
CREATE TRIGGER trg_iig_employee_assignment_reference
  AFTER UPDATE OF full_name ON employees
  FOR EACH ROW WHEN (OLD.full_name IS DISTINCT FROM NEW.full_name)
  EXECUTE FUNCTION iig_enqueue_assignment_reference_change();
DROP TRIGGER IF EXISTS trg_iig_class_assignment_reference ON classes;
CREATE TRIGGER trg_iig_class_assignment_reference
  AFTER UPDATE OF class_code,name ON classes
  FOR EACH ROW WHEN (OLD.class_code IS DISTINCT FROM NEW.class_code OR OLD.name IS DISTINCT FROM NEW.name)
  EXECUTE FUNCTION iig_enqueue_assignment_reference_change();
DROP TRIGGER IF EXISTS trg_iig_staff_role_assignment_reference ON users;
CREATE TRIGGER trg_iig_staff_role_assignment_reference
  AFTER UPDATE OF role_id ON users
  FOR EACH ROW WHEN (OLD.role_id IS DISTINCT FROM NEW.role_id)
  EXECUTE FUNCTION iig_enqueue_assignment_reference_change();

package vn.iig.attendance;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL immutable event history trigger. Spring's script initializer
 * splits `schema.sql` on semicolons even inside PL/pgSQL dollar quotes;
 * execute the complete function definition as one JDBC statement instead.
 *
 * Failure aborts startup. No event can be imported by the scheduled relay
 * before ApplicationRunner successfully installs the database guard.
 */
@Component
public class AttendanceHistoryImmutability implements ApplicationRunner {
    private final JdbcTemplate db;

    public AttendanceHistoryImmutability(JdbcTemplate db) {
        this.db=db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.execute("""
            CREATE OR REPLACE FUNCTION iig_attendance_block_history_mutations()
            RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'Attendance historical events are immutable'
                USING ERRCODE='55000';
            END;
            $$;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_attendance_immutable_history " +
            "ON attendance_teacher_event_history");
        db.execute("CREATE TRIGGER trg_iig_attendance_immutable_history " +
            "BEFORE UPDATE OR DELETE ON attendance_teacher_event_history " +
            "FOR EACH ROW EXECUTE FUNCTION iig_attendance_block_history_mutations()");

        // Once the native writer becomes active, its original domain event,
        // canonical bytes and claim identity are immutable even to direct SQL
        // updates. Only one exact reference confirmation is permissible.
        db.execute("""
            CREATE OR REPLACE FUNCTION iig_attendance_native_outbox_finality()
            RETURNS TRIGGER LANGUAGE plpgsql AS $
            BEGIN
              IF TG_OP='DELETE' OR
                 NEW.event_id IS DISTINCT FROM OLD.event_id OR
                 NEW.actor_username IS DISTINCT FROM OLD.actor_username OR
                 NEW.class_id IS DISTINCT FROM OLD.class_id OR
                 NEW.teaching_schedule_id IS DISTINCT FROM OLD.teaching_schedule_id OR
                 NEW.request_key IS DISTINCT FROM OLD.request_key OR
                 NEW.version IS DISTINCT FROM OLD.version OR
                 NEW.payload IS DISTINCT FROM OLD.payload OR
                 NEW.payload_canonical IS DISTINCT FROM OLD.payload_canonical OR
                 NEW.payload_hash IS DISTINCT FROM OLD.payload_hash OR
                 NEW.created_at IS DISTINCT FROM OLD.created_at OR
                 (OLD.confirmed_at IS NOT NULL AND
                    (NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at OR
                     NEW.reference_receipt IS DISTINCT FROM OLD.reference_receipt)) OR
                 (NEW.confirmed_at IS NOT NULL AND NEW.reference_receipt IS NULL)
              THEN
                RAISE EXCEPTION 'Native Attendance reference proof is immutable'
                  USING ERRCODE='55000';
              END IF;
              RETURN NEW;
            END;
            $;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_attendance_native_outbox_finality " +
            "ON attendance_native_outbox");
        db.execute("CREATE TRIGGER trg_iig_attendance_native_outbox_finality " +
            "BEFORE UPDATE OR DELETE ON attendance_native_outbox " +
            "FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_outbox_finality()");

        db.execute("""
            CREATE OR REPLACE FUNCTION iig_attendance_native_command_finality()
            RETURNS TRIGGER LANGUAGE plpgsql AS $
            BEGIN
              IF TG_OP='DELETE' OR
                 NEW.actor_username IS DISTINCT FROM OLD.actor_username OR
                 NEW.class_id IS DISTINCT FROM OLD.class_id OR
                 NEW.teaching_schedule_id IS DISTINCT FROM OLD.teaching_schedule_id OR
                 NEW.request_key IS DISTINCT FROM OLD.request_key OR
                 NEW.request_hash IS DISTINCT FROM OLD.request_hash OR
                 NEW.facts_token IS DISTINCT FROM OLD.facts_token OR
                 NEW.writer_generation IS DISTINCT FROM OLD.writer_generation OR
                 NEW.event_id IS DISTINCT FROM OLD.event_id OR
                 NEW.created_at IS DISTINCT FROM OLD.created_at OR
                 (NEW.state IS DISTINCT FROM OLD.state AND
                  NOT (OLD.state='PENDING_REFERENCE' AND NEW.state='COMMITTED'
                       AND OLD.completed_at IS NULL AND NEW.completed_at IS NOT NULL)) OR
                 (OLD.state='COMMITTED' AND
                  NEW.completed_at IS DISTINCT FROM OLD.completed_at)
              THEN
                RAISE EXCEPTION 'Native Attendance command finality is immutable'
                  USING ERRCODE='55000';
              END IF;
              RETURN NEW;
            END;
            $;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_attendance_native_command_finality " +
            "ON attendance_native_commands");
        db.execute("CREATE TRIGGER trg_iig_attendance_native_command_finality " +
            "BEFORE UPDATE OR DELETE ON attendance_native_commands " +
            "FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_command_finality()");

        db.execute("""
            CREATE OR REPLACE FUNCTION iig_attendance_native_row_version()
            RETURNS TRIGGER LANGUAGE plpgsql AS $
            BEGIN
              IF TG_OP='DELETE' OR NEW.teaching_schedule_id IS DISTINCT FROM
                 OLD.teaching_schedule_id
                 OR NEW.attendance_id IS DISTINCT FROM OLD.attendance_id
                 OR (OLD.recorded_at IS NOT NULL AND
                     NEW.recorded_at IS DISTINCT FROM OLD.recorded_at)
                 OR NEW.version<>OLD.version+1
                 OR NEW.updated_at<OLD.updated_at THEN
                RAISE EXCEPTION 'Native Attendance domain version cannot regress'
                  USING ERRCODE='55000';
              END IF;
              RETURN NEW;
            END;
            $;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_attendance_native_row_version " +
            "ON attendance_native_teacher_rows");
        db.execute("CREATE TRIGGER trg_iig_attendance_native_row_version " +
            "BEFORE UPDATE OR DELETE ON attendance_native_teacher_rows " +
            "FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_row_version()");
        db.execute("""
            CREATE OR REPLACE FUNCTION iig_attendance_native_no_truncate()
            RETURNS TRIGGER LANGUAGE plpgsql AS $
            BEGIN
              RAISE EXCEPTION 'Native Attendance journal cannot be truncated'
                USING ERRCODE='55000';
            END;
            $;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_native_activation_immutable " +
            "ON attendance_native_activations");
        db.execute("CREATE TRIGGER trg_iig_native_activation_immutable " +
            "BEFORE UPDATE OR DELETE ON attendance_native_activations " +
            "FOR EACH ROW EXECUTE FUNCTION iig_attendance_native_no_truncate()");
        for(String table:java.util.List.of("attendance_native_teacher_rows",
                "attendance_native_commands","attendance_native_outbox",
                "attendance_native_activations")) {
            String trigger="trg_iig_no_truncate_"+table;
            db.execute("DROP TRIGGER IF EXISTS "+trigger+" ON "+table);
            db.execute("CREATE TRIGGER "+trigger+" BEFORE TRUNCATE ON "+table+
                " FOR EACH STATEMENT EXECUTE FUNCTION iig_attendance_native_no_truncate()");
        }

    }
}

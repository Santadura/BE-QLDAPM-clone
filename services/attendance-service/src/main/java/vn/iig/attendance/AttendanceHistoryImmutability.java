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
    }
}

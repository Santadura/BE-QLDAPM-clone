package vn.iig.payroll;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fail-closed, PostgreSQL-level immutability for Payroll's imported teacher
 * evidence history. Execute PL/pgSQL in a single JDBC statement because
 * Spring schema.sql semicolon splitting corrupts dollar-quoted functions.
 */
@Component
public class PayrollAttendanceHistoryGuard implements ApplicationRunner {
    private final JdbcTemplate db;
    public PayrollAttendanceHistoryGuard(JdbcTemplate db) { this.db=db; }

    @Override
    public void run(ApplicationArguments args) {
        db.execute("""
            CREATE OR REPLACE FUNCTION iig_payroll_block_attendance_evidence_mutations()
            RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN
              RAISE EXCEPTION 'Payroll attendance evidence history is immutable'
                USING ERRCODE='55000';
            END;
            $$;
            """);
        db.execute("DROP TRIGGER IF EXISTS trg_iig_payroll_immutable_attendance_history " +
            "ON payroll_attendance_reference_events");
        db.execute("CREATE TRIGGER trg_iig_payroll_immutable_attendance_history " +
            "BEFORE UPDATE OR DELETE ON payroll_attendance_reference_events " +
            "FOR EACH ROW EXECUTE FUNCTION iig_payroll_block_attendance_evidence_mutations()");
    }
}

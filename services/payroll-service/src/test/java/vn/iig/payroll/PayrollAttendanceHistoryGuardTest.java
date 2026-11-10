package vn.iig.payroll;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PayrollAttendanceHistoryGuardTest {
    @Test void installImmutablePostgresHistoryTriggerAtStartup() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        new PayrollAttendanceHistoryGuard(db).run(null);
        var order=inOrder(db);
        order.verify(db).execute(startsWith("CREATE OR REPLACE FUNCTION"));
        order.verify(db).execute(startsWith("DROP TRIGGER IF EXISTS"));
        order.verify(db).execute(startsWith("CREATE TRIGGER"));
    }

    @Test void databaseOutageMustFailStartupInsteadOfSkippingGuard() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("database offline"))
            .when(db).execute(anyString());
        assertThrows(IllegalStateException.class,
            ()->new PayrollAttendanceHistoryGuard(db).run(null));
    }
}

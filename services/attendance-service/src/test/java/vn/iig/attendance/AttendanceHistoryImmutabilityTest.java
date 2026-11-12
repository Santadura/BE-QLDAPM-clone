package vn.iig.attendance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttendanceHistoryImmutabilityTest {
    @Test void installsImmutableSqlFunctionAndTriggerBeforeRelayCanRun() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        new AttendanceHistoryImmutability(db).run(null);
        var order=inOrder(db);
        order.verify(db).execute(org.mockito.ArgumentMatchers.<String>argThat(sql->
            sql.contains("CREATE OR REPLACE FUNCTION iig_attendance_block_history_mutations") &&
            sql.contains("ERRCODE='55000'") && sql.contains("$$;")));
        order.verify(db).execute(startsWith(
            "DROP TRIGGER IF EXISTS trg_iig_attendance_immutable_history"));
        order.verify(db).execute(startsWith(
            "CREATE TRIGGER trg_iig_attendance_immutable_history"));
    }

    @Test void databaseFunctionFailureAbortsSetupWithoutFalseProtection() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("PostgreSQL unavailable"))
            .when(db).execute(contains("CREATE OR REPLACE FUNCTION"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            ()->new AttendanceHistoryImmutability(db).run(null));
        verify(db,never()).execute(startsWith("CREATE TRIGGER"));
    }
    @Test void nativeFunctionBodiesHaveValidPostgreSQLDoubleDollarQuoting() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        new AttendanceHistoryImmutability(db).run(null);
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db,atLeast(5)).execute(sql.capture());
        var functions=sql.getAllValues().stream()
            .filter(x->x.contains("RETURNS TRIGGER LANGUAGE plpgsql")).toList();
        org.junit.jupiter.api.Assertions.assertEquals(5,functions.size());
        for(var function:functions) {
            org.junit.jupiter.api.Assertions.assertTrue(
                function.contains("RETURNS TRIGGER LANGUAGE plpgsql AS $$"));
            org.junit.jupiter.api.Assertions.assertTrue(function.contains("$$;"));
            org.junit.jupiter.api.Assertions.assertFalse(
                function.contains("LANGUAGE plpgsql AS $\n"));
        }
    }

}

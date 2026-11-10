package vn.iig.payroll;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import vn.iig.payroll.PayrollAttendanceEvidenceClient.*;

class PayrollAttendanceReferenceRepositoryTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final PayrollAttendanceReferenceRepository repo=
        new PayrollAttendanceReferenceRepository(db);

    private static HistoryPage page(HistoryEvent... events) {
        return new HistoryPage(9,3,2,4,List.of(events));
    }
    private static HistoryEvent event(long id,String kind,String status) {
        return new HistoryEvent(id,"teach-1",kind,status,"a".repeat(64));
    }

    private void cursor0() {
        when(db.queryForObject(startsWith("SELECT last_event_id"),
            eq(Long.class))).thenReturn(0L);
        when(db.update(startsWith("UPDATE payroll_attendance_reference_cursor"),
            anyLong(),anyLong())).thenReturn(1);
    }

    @Test void appendAndLatestProjectionMustAdvanceCursorInSameTransaction() {
        cursor0();
        repo.apply(page(event(1,"UPSERT","PRESENT"),event(3,"DELETE",null)));
        verify(db,times(2)).update(startsWith(
            "INSERT INTO payroll_attendance_reference_events"),any(Object[].class));
        verify(db,times(2)).update(startsWith(
            "INSERT INTO payroll_attendance_reference_latest"),any(Object[].class));
        verify(db).update(startsWith("UPDATE payroll_attendance_reference_cursor"),
            eq(3L),eq(0L));
    }

    @Test void sourceEventIdCollisionCannotOverwriteProvenance() {
        cursor0();
        when(db.query(startsWith("SELECT teaching_schedule_id,event_id"),
            any(RowMapper.class),eq(1L))).thenReturn(List.of(
                new PayrollAttendanceReferenceRepository.Reference(
                    "teach-1",1L,"UPSERT","ABSENT","b".repeat(64))));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.apply(page(event(1,"UPSERT","PRESENT")))).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO payroll_attendance_reference_events"),
            any(Object[].class));
        verify(db,never()).update(startsWith("UPDATE payroll_attendance_reference_cursor"),
            any(Object[].class));
    }

    @Test void malformedEventRollsBackBeforeWritingAnyHistory() {
        cursor0();
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.apply(page(event(3,"DELETE","PRESENT")))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void failedCursorWriteMustAbortEntireDatabasePageTransaction() {
        when(db.queryForObject(startsWith("SELECT last_event_id"),
            eq(Long.class))).thenReturn(0L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->repo.apply(page(event(1,"UPSERT","PRESENT")))).getStatusCode());
    }
}

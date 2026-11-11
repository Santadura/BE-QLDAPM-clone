package vn.iig.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeacherAcademicLeaseCompletionServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherAcademicLeaseCompletionService service=
        new TeacherAcademicLeaseCompletionService(db,new ObjectMapper(),core);

    private final String payload=
        "{\"id\":\"shift-1\",\"teacherId\":\"teacher-001\","+
        "\"classId\":\"class-1\",\"date\":\"2027-02-20\","+
        "\"startTime\":\"09:00\",\"endTime\":\"11:00\","+
        "\"status\":\"ASSIGNED\",\"assignedBy\":\"tc-001\"}";

    @SuppressWarnings({"rawtypes","unchecked"})
    private void ready() throws Exception {
        when(db.query(contains("JOIN scheduling_assignment_domain_outbox e"),
            any(RowMapper.class),eq("tc001"),eq("key")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("lease-1");
                when(rs.getString(2)).thenReturn("evt-1");
                when(rs.getString(3)).thenReturn("shift-1");
                when(rs.getLong(4)).thenReturn(1L);
                when(rs.getString(5)).thenReturn("a".repeat(64));
                when(rs.getString(6)).thenReturn(payload);
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.queryForObject(startsWith(
            "SELECT COUNT(*) FROM scheduling_assignment_domain_outbox"),
            eq(Long.class),eq("tc001"),eq("key"))).thenReturn(1L);
    }

    private void validCoreReceipt() {
        when(core.committedAssignmentReceipt("TEACHING","shift-1","evt-1"))
            .thenReturn(new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
                "evt-1",1L,"a".repeat(64),"teacher-001","class-1",
                LocalDate.of(2027,2,20),LocalTime.of(9,0),LocalTime.of(11,0),
                "ASSIGNED","tc-001"));
    }

    @Test void verifiesExactCoreReceiptThenIdempotentlyReleasesBoundLease()
            throws Exception {
        ready();validCoreReceipt();
        when(db.update(startsWith(
            "DELETE FROM scheduling_academic_command_leases"),
            eq("tc001"),eq("key"),eq("lease-1"))).thenReturn(1);
        assertTrue(service.complete("tc001","key"));
        var ordered=inOrder(core,db);
        ordered.verify(core).committedAssignmentReceipt(
            "TEACHING","shift-1","evt-1");
        ordered.verify(core).releaseAcademicSession("lease-1");
        ordered.verify(db).update(startsWith(
            "DELETE FROM scheduling_academic_command_leases"),
            eq("tc001"),eq("key"),eq("lease-1"));
    }

    @Test void mismatchedHashBlocksLeaseReleaseWithoutRemovingLocalCursor()
            throws Exception {
        ready();validCoreReceipt();
        when(core.committedAssignmentReceipt("TEACHING","shift-1","evt-1"))
            .thenReturn(new SchedulingCoreClient.DurableAssignmentReferenceReceipt(
                "evt-1",1L,"f".repeat(64)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->service.complete("tc001","key")).getStatusCode());
        verify(core,never()).releaseAcademicSession(anyString());
        verify(db,never()).update(startsWith(
            "DELETE FROM scheduling_academic_command_leases"),any(),any(),any());
    }

    @Test void lostCoreDeleteResponsePreservesRetryableAcademicLease() throws Exception {
        ready();validCoreReceipt();
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            .when(core).releaseAcademicSession("lease-1");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->service.complete("tc001","key")).getStatusCode());
        verify(db,never()).update(startsWith(
            "DELETE FROM scheduling_academic_command_leases"),any(),any(),any());
    }

    @Test void noCommittedTeacherEventCannotReleaseAnAcademicLease() {
        assertFalse(service.complete("tc001","key"));
        verifyNoInteractions(core);
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void malformedScopedClaimNeverTouchesCoreOrDatabase() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.complete("bad actor","key")).getStatusCode());
        verifyNoInteractions(db,core);
    }
}

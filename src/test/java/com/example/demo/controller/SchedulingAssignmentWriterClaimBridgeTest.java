package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAssignmentWriterClaimBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAssignmentWriterClaimBridge controller=
        new SchedulingAssignmentWriterClaimBridge(db,"private-writer-key");

    private SchedulingAssignmentWriterClaimBridge.ClaimRequest command() {
        return new SchedulingAssignmentWriterClaimBridge.ClaimRequest(
            "cm001","cm-001","ASSIGN_STAFF","cmd-1");
    }

    private void allowed() {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.queryForObject(contains("AND u.role_id=?"),
            eq(Long.class),eq("cm-001"),eq("cm001"),eq("CM"))).thenReturn(1L);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void existing(String manager,String state) throws Exception {
        when(db.query(startsWith("SELECT manager_employee_id,status"),
            any(RowMapper.class),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(manager);
                when(rs.getString(2)).thenReturn(state);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void privateKeyIsCheckedBeforeAnyDatabaseActivity() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->controller.acquire("wrong",command())).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void CoreMustBeQuiescedWhenClaimIsDurablyWritten() {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("CORE");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->controller.acquire("private-writer-key",command())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void InactiveManagerCanNeverReserveIndependentWriterSlot() {
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.queryForObject(contains("AND u.role_id=?"),
            eq(Long.class),eq("cm-001"),eq("cm001"),eq("CM"))).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                ()->controller.acquire("private-writer-key",command())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void ClaimTransactionUsesCoreWriterRowLockBeforeDurableAdmission()
            throws Exception {
        allowed();
        existing("cm-001","PENDING");
        var receipt=controller.acquire("private-writer-key",command());
        assertEquals("PENDING",receipt.status());
        assertEquals("cmd-1",receipt.requestKey());
        var order=inOrder(db);
        order.verify(db).queryForObject(
            contains("FOR UPDATE"),eq(String.class));
        order.verify(db).queryForObject(
            contains("AND u.role_id=?"),
            eq(Long.class),eq("cm-001"),eq("cm001"),eq("CM"));
        order.verify(db).update(
            startsWith("INSERT INTO scheduling_assignment_writer_claims"),
            eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1"),eq("cm-001"));
        order.verify(db).query(startsWith("SELECT manager_employee_id,status"),
            any(RowMapper.class),eq("cm001"),eq("ASSIGN_STAFF"),eq("cmd-1"));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void coreClaimDiagnosticIsReadOnlyAndReportsUnconfirmedOrphans() throws Exception {
        when(db.query(startsWith("SELECT manager_employee_id,status"),
            any(RowMapper.class),eq("tc001"),eq("orphan-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("tc-1");
                when(rs.getString(2)).thenReturn("PENDING");
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.query(startsWith("SELECT event_id,schedule_id,payload_hash"),
            any(RowMapper.class),eq("tc001"),eq("orphan-1")))
            .thenReturn(List.of());
        var report=controller.diagnoseTeacher(
            "private-writer-key","tc001","orphan-1");
        assertTrue(report.found());
        assertEquals("PENDING",report.claimStatus());
        assertEquals(0,report.referenceEventCount());
        assertFalse(report.coreTeacherRowExists());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void coreClaimDiagnosticRequiresServiceKeyBeforeDatabaseRead() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.diagnoseTeacher("wrong","tc001","cmd-1")).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void SameCommandAndManagerCanRetryAfterLostHttpResponse() throws Exception {
        allowed();
        existing("cm-001","PENDING");
        var first=controller.acquire("private-writer-key",command());
        var second=controller.acquire("private-writer-key",command());
        assertEquals(first,second);
    }

    @Test void AReusedCommandKeyCannotChangeItsOriginalManager() throws Exception {
        allowed();
        existing("somebody-else","PENDING");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->controller.acquire("private-writer-key",command())).getStatusCode());
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void liveTcMayReserveIndependentTeacherClaimBeforeLocalWrite()
            throws Exception {
        var command=new SchedulingAssignmentWriterClaimBridge.ClaimRequest(
            "tc001","tc-001","ASSIGN_TEACHER","teacher-command-1");
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.queryForObject(contains("AND u.role_id=?"),
            eq(Long.class),eq("tc-001"),eq("tc001"),eq("TC"))).thenReturn(1L);
        when(db.query(startsWith("SELECT manager_employee_id,status"),
            any(RowMapper.class),eq("tc001"),eq("ASSIGN_TEACHER"),
            eq("teacher-command-1"))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("tc-001");
                when(rs.getString(2)).thenReturn("PENDING");
                return List.of(mapper.mapRow(rs,0));
            });
        var receipt=controller.acquire("private-writer-key",command);
        assertEquals("PENDING",receipt.status());
        verify(db).update(startsWith("INSERT INTO scheduling_assignment_writer_claims"),
            eq("tc001"),eq("ASSIGN_TEACHER"),eq("teacher-command-1"),eq("tc-001"));
    }

    @Test void aCmCannotUseATeacherWriterClaimToAcquireTcPrivileges() {
        var command=new SchedulingAssignmentWriterClaimBridge.ClaimRequest(
            "cm001","cm-001","ASSIGN_TEACHER","teacher-command-1");
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        when(db.queryForObject(contains("AND u.role_id=?"),
            eq(Long.class),eq("cm-001"),eq("cm001"),eq("TC"))).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.acquire("private-writer-key",command)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_assignment_writer_claims"),
            any(Object[].class));
    }

    @Test void aTcCannotUseAStaffWriteClaimToAssumeCmPrivileges() {
        var command=new SchedulingAssignmentWriterClaimBridge.ClaimRequest(
            "tc001","tc-001","ASSIGN_STAFF","teacher-command-1");
        when(db.queryForObject(startsWith("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->controller.acquire("private-writer-key",command)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_assignment_writer_claims"),
            any(Object[].class));
    }
}

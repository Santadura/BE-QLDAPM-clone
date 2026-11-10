package com.example.demo.service;

import com.example.demo.exception.ApiException;
import com.example.demo.service.TeacherSessionAttendanceService.MarkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherAttendanceCommandReceiptServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAccess access=mock(SchedulingAccess.class);
    private final TeacherSessionAttendanceService attendance=mock(TeacherSessionAttendanceService.class);
    private final TeacherAttendanceLiveFactsService facts=mock(TeacherAttendanceLiveFactsService.class);
    private final TeacherAttendanceCommandReceiptService receipts=
        new TeacherAttendanceCommandReceiptService(attendance,access,db,new ObjectMapper(),facts);

    private void cs() {
        when(access.requireRole("CS")).thenReturn(
            new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
    }
    private Map<String,Object> row(String hash,String payload) {
        Map<String,Object> result=new HashMap<>();
        result.put("request_hash",hash);
        result.put("response_payload",payload);
        return result;
    }

    @Test void sameKeyReplaysExactCommittedResultWithoutSecondAttendanceWrite() {
        cs();
        String hash=TeacherAttendanceCommandReceiptService.fingerprint("class-1","teach-1","PRESENT");
        when(db.queryForMap(startsWith("SELECT request_hash"),any(Object[].class)))
            .thenReturn(row(hash,null),row(hash,"{\"status\":\"PRESENT\"}"));
        when(db.update(startsWith("UPDATE teacher_attendance_command_receipts"),
            any(Object[].class))).thenReturn(1);
        when(attendance.mark(eq("class-1"),eq("teach-1"),any(MarkRequest.class)))
            .thenReturn(Map.of("status","PRESENT"));
        var first=receipts.mark("class-1","teach-1","request-1",null,new MarkRequest(" present "));
        var retry=receipts.mark("class-1","teach-1","request-1",null,new MarkRequest("PRESENT"));
        assertEquals(first,retry);
        verify(attendance,times(1)).mark(eq("class-1"),eq("teach-1"),any(MarkRequest.class));
        verify(attendance).requireCurrentSupportForReplay("class-1","teach-1","cs-001");
        verify(db,times(1)).update(startsWith("UPDATE teacher_attendance_command_receipts"),
            any(Object[].class));
    }

    @Test void changedPayloadWithSameKeyConflictsBeforeAttendanceMutation() {
        cs();
        String first=TeacherAttendanceCommandReceiptService.fingerprint("class-1","teach-1","PRESENT");
        when(db.queryForMap(startsWith("SELECT request_hash"),any(Object[].class)))
            .thenReturn(row(first,"{\"status\":\"PRESENT\"}"));
        var error=assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","request-1",null,new MarkRequest("ABSENT")));
        assertEquals(HttpStatus.CONFLICT,error.getStatus());
        verifyNoInteractions(attendance);
    }

    @Test void invalidKeyOrStatusCannotCreateReceipt() {
        cs();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","bad key",null,new MarkRequest("PRESENT"))).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","request-1",null,new MarkRequest("UNKNOWN"))).getStatus());
        verifyNoInteractions(db,attendance);
    }

    @Test void revokedSupportCannotUseOldSuccessfulReceipt() {
        cs();
        String hash=TeacherAttendanceCommandReceiptService.fingerprint("class-1","teach-1","PRESENT");
        when(db.queryForMap(startsWith("SELECT request_hash"),any(Object[].class)))
            .thenReturn(row(hash,"{\"status\":\"PRESENT\"}"));
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"No active CS shift"))
            .when(attendance).requireCurrentSupportForReplay("class-1","teach-1","cs-001");
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","request-1",null,new MarkRequest("PRESENT"))).getStatus());
        verify(attendance,never()).mark(anyString(),anyString(),any(MarkRequest.class));
    }

    @Test void staleVersionAfterLockedAttendanceMarkRollsBackCoreTransaction() {
        cs();
        String token="a".repeat(64);
        when(db.queryForMap(startsWith("SELECT request_hash"),any(Object[].class)))
            .thenReturn(row(TeacherAttendanceCommandReceiptService.fingerprint(
                "class-1","teach-1","PRESENT"),null));
        when(attendance.mark(eq("class-1"),eq("teach-1"),any(MarkRequest.class)))
            .thenReturn(Map.of("status","PRESENT"));
        doThrow(new ApiException(HttpStatus.CONFLICT,"Lesson changed"))
            .when(facts).requireUnchanged("class-1","teach-1",token);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","request-1",token,
                new MarkRequest("PRESENT"))).getStatus());
        verify(db,never()).update(startsWith("UPDATE teacher_attendance_command_receipts"),
            any(Object[].class));
        // Real PostgreSQL @Transactional rollback is verified by Docker smoke.
    }

    @Test void disabledOrWrongRoleNeverReadsReceipts() {
        when(access.requireRole("CS")).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"Role revoked"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
            ()->receipts.mark("class-1","teach-1","request-1",null,new MarkRequest("PRESENT"))).getStatus());
        verifyNoInteractions(db,attendance);
    }
}

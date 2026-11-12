package com.example.demo.controller;

import com.example.demo.exception.ApiException;
import com.example.demo.service.SchedulingAccess;
import com.example.demo.service.TeacherAttendanceLiveFactsService;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeacherAttendanceNativeClaimControllerTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAccess access=mock(SchedulingAccess.class);
    private final TeacherAttendanceLiveFactsService facts=
        mock(TeacherAttendanceLiveFactsService.class);
    private final TeacherAttendanceNativeClaimController controller=
        new TeacherAttendanceNativeClaimController(db,access,facts,"private-key");

    @Test void privateOrphanScanRequiresExactServiceKeyBeforeReadingCore() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.recoverable(null,25)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->controller.recoverable("forged",25)).getStatusCode());
        verifyNoInteractions(db,facts);
    }

    @Test void unboundedOrphanScanRejectedBeforeDatabaseAccess() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->controller.recoverable("private-key",100)).getStatusCode());
        verifyNoInteractions(db,facts);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void durableCoreClaim() throws Exception {
        when(db.query(contains("c.state='OPEN' AND w.writer_mode='ATTENDANCE'"),
                any(RowMapper.class),eq(25))).thenAnswer(call->{
            RowMapper mapper=call.getArgument(1);
            ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn("11111111-1111-1111-1111-111111111111");
            when(row.getString(2)).thenReturn("cs001");
            when(row.getString(3)).thenReturn("employee-1");
            when(row.getString(4)).thenReturn("class-1");
            when(row.getString(5)).thenReturn("teach-1");
            when(row.getString(6)).thenReturn("request-1");
            when(row.getString(7)).thenReturn(
                TeacherAttendanceNativeClaimController.digest(
                    "class-1","teach-1","PRESENT"));
            when(row.getString(8)).thenReturn("PRESENT");
            when(row.getString(9)).thenReturn("a".repeat(64));
            when(row.getLong(10)).thenReturn(2L);
            when(row.getString(11)).thenReturn(null);
            when(row.getString(12)).thenReturn(null);
            when(row.getString(13)).thenReturn("OPEN");
            return List.of(mapper.mapRow(row,0));
        });
    }

    private TeacherAttendanceLiveFactsService.Facts live(String token) {
        return new TeacherAttendanceLiveFactsService.Facts(
            "class-1","teach-1","session-1",1,1,1,
            "support-1",1,"employee-1",
            LocalDate.of(2026,11,10),LocalTime.of(8,0),LocalTime.of(10,0),
            2,3,token);
    }

    @Test void onlyStillValidCoreClaimIsRecoverableWithoutOriginalBearer()
            throws Exception {
        durableCoreClaim();
        when(facts.nativeForClaim("class-1","teach-1","cs001","employee-1"))
            .thenReturn(live("a".repeat(64)));
        var claims=controller.recoverable("private-key",25);
        assertEquals(1,claims.size());
        assertEquals("OPEN",claims.get(0).state());
        assertEquals("ATTENDANCE",claims.get(0).ownerMode());
        assertEquals("request-1",claims.get(0).requestKey());
        verify(db,never()).update(anyString(),any(Object[].class));
        verifyNoInteractions(access);
    }

    @Test void claimWithRevokedRoleRemainsOpenAndIsNotSentToWorker() throws Exception {
        durableCoreClaim();
        when(facts.nativeForClaim("class-1","teach-1","cs001","employee-1"))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN,"Revoked CS role"));
        assertTrue(controller.recoverable("private-key",25).isEmpty());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void changedAcademicVersionTokenDoesNotProduceFalseOrphanWrite()
            throws Exception {
        durableCoreClaim();
        when(facts.nativeForClaim("class-1","teach-1","cs001","employee-1"))
            .thenReturn(live("b".repeat(64)));
        assertTrue(controller.recoverable("private-key",25).isEmpty());
    }
}

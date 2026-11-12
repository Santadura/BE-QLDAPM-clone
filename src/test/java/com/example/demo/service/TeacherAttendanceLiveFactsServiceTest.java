package com.example.demo.service;

import com.example.demo.exception.ApiException;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked","rawtypes"})
class TeacherAttendanceLiveFactsServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAccess access=mock(SchedulingAccess.class);
    private final TeacherAttendanceLiveFactsService facts=
        new TeacherAttendanceLiveFactsService(db,access);
    private final LocalDate day=LocalDate.of(2026,10,6);
    private final LocalTime start=LocalTime.of(18,30);
    private final LocalTime end=LocalTime.of(20,30);
    private final AtomicLong supportVersion=new AtomicLong(2);

    private void activeCs() {
        when(access.requireRole("CS")).thenReturn(
            new SchedulingAccess.StaffIdentity("cs-001","cs001","CS"));
    }

    private void plannedLesson() throws Exception {
        when(db.query(startsWith("SELECT lesson.class_session_id"),
            any(RowMapper.class),eq("teach-1"),eq("class-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("session-1");
                when(rs.getString(2)).thenReturn("teacher-1");
                when(rs.getLong(3)).thenReturn(4L);
                when(rs.getString(4)).thenReturn("RUNNING");
                when(rs.getLong(5)).thenReturn(7L);
                when(rs.getObject(6,LocalDate.class)).thenReturn(day);
                when(rs.getObject(7,LocalTime.class)).thenReturn(start);
                when(rs.getObject(8,LocalTime.class)).thenReturn(end);
                when(rs.getLong(9)).thenReturn(9L);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private void exactSupport() throws Exception {
        when(db.query(startsWith("SELECT s.staff_schedule_id"),
            any(RowMapper.class),eq("cs-001"),eq("class-1"),
            eq(day),eq(start),eq(end))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("support-1");
                when(rs.getLong(2)).thenAnswer(x->supportVersion.get());
                return List.of(mapper.mapRow(rs,0));
            });
    }

    private void coreModes(String attendanceMode,String schedulingMode) throws Exception {
        coreModes(attendanceMode,schedulingMode,"CORE");
    }
    private void coreModes(String attendanceMode,String schedulingMode,
                           String academicMode) throws Exception {
        when(db.queryForObject(startsWith("SELECT a.writer_mode"),
            any(RowMapper.class))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(attendanceMode);
                when(rs.getLong(2)).thenReturn(3L);
                when(rs.getString(3)).thenReturn(schedulingMode);
                when(rs.getLong(4)).thenReturn(5L);
                when(rs.getString(5)).thenReturn(academicMode);
                when(rs.getLong(6)).thenReturn(9L);
                return mapper.mapRow(rs,0);
            });
    }

    @Test void sourceFactsBindExactCsTeacherSessionAndFenceGenerations() throws Exception {
        activeCs();plannedLesson();exactSupport();coreModes("CORE","CORE");
        var result=facts.current("class-1","teach-1");
        assertEquals("session-1",result.sessionId());
        assertEquals(4L,result.classVersion());
        assertEquals(7L,result.sessionVersion());
        assertEquals(9L,result.teacherVersion());
        assertEquals("support-1",result.supportScheduleId());
        assertEquals(2L,result.supportVersion());
        assertEquals("cs-001",result.actorEmployeeId());
        assertTrue(result.token().matches("[0-9a-f]{64}"));
    }

    @Test void changedStaffRevisionInvalidatesPreviouslyReadToken() throws Exception {
        activeCs();plannedLesson();exactSupport();coreModes("CORE","CORE");
        var initial=facts.current("class-1","teach-1").token();
        supportVersion.incrementAndGet();
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->facts.requireUnchanged("class-1","teach-1",initial)).getStatus());
        assertDoesNotThrow(()->facts.requireUnchanged(
            "class-1","teach-1",facts.current("class-1","teach-1").token()));
    }

    @Test void noExactAssignedCsSupportCannotProduceVersionedPreflight() throws Exception {
        activeCs();plannedLesson();
        var error=assertThrows(ApiException.class,
            ()->facts.current("class-1","teach-1"));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatus());
        verify(db,never()).queryForObject(startsWith("SELECT a.writer_mode"),
            any(RowMapper.class));
    }

    @Test void quiescedCoreCanNeverIssueNewWriteCandidate() throws Exception {
        activeCs();plannedLesson();exactSupport();coreModes("QUIESCED","QUIESCED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->facts.current("class-1","teach-1")).getStatus());
    }

    @Test void invalidOrChangedActorCannotObtainFacts() {
        when(access.requireRole("CS")).thenThrow(
            new ApiException(HttpStatus.FORBIDDEN,"CS role revoked"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
            ()->facts.current("class-1","teach-1")).getStatus());
        verifyNoInteractions(db);
    }

    @Test void malformedTokenFailsBeforeAnyDatabaseLookup() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ApiException.class,
            ()->facts.requireUnchanged("class-1","teach-1","forged")).getStatus());
        verifyNoInteractions(db,access);
    }
    @Test void nativeCSProofRequiresExclusiveAttendanceAndFrozenAcademic() throws Exception {
        activeCs();plannedLesson();exactSupport();
        coreModes("ATTENDANCE","QUIESCED","QUIESCED");
        var proof=facts.nativeCurrent("class-1","teach-1");
        assertTrue(proof.token().matches("[0-9a-f]{64}"));
        assertEquals(3,proof.attendanceGeneration());
        assertEquals("cs-001",proof.actorEmployeeId());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->facts.current("class-1","teach-1")).getStatus());
    }

    @Test void nativeCSProofNeverSucceedsWhileLegacyCoreWriterOrAcademicRuns()
            throws Exception {
        activeCs();plannedLesson();exactSupport();
        coreModes("CORE","QUIESCED","QUIESCED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->facts.nativeCurrent("class-1","teach-1")).getStatus());
        coreModes("ATTENDANCE","QUIESCED","CORE");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,
            ()->facts.nativeCurrent("class-1","teach-1")).getStatus());
    }

    @Test void CoreProjectionMustRecheckOriginalCsClaimAgainstActiveRole()
            throws Exception {
        plannedLesson();exactSupport();coreModes("ATTENDANCE","QUIESCED","QUIESCED");
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM users u JOIN employees e"),
            eq(Long.class),eq("cs001"),eq("cs-001"))).thenReturn(1L);
        var proof=facts.nativeForClaim("class-1","teach-1","cs001","cs-001");
        assertEquals("cs-001",proof.actorEmployeeId());
        verify(access,never()).requireRole(anyString());
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM users u JOIN employees e"),
            eq(Long.class),eq("cs001"),eq("cs-001"))).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ApiException.class,
            ()->facts.nativeForClaim("class-1","teach-1","cs001","cs-001"))
            .getStatus());
    }

}

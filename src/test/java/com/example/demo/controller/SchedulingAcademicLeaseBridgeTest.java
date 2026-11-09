package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAcademicLeaseBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingAcademicLeaseBridge bridge=
        new SchedulingAcademicLeaseBridge(db,"trusted-academic-key");
    private final SchedulingAcademicLeaseBridge.Request request=
        new SchedulingAcademicLeaseBridge.Request("class-1","lesson-1",3,7,"command-1");

    @SuppressWarnings({"rawtypes","unchecked"})
    private void classAndLesson(long cv,long sv,String status,String lessonStatus)
            throws Exception {
        when(db.query(contains("FROM classes WHERE class_id=? FOR UPDATE"),
                any(RowMapper.class),eq("class-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(cv);
                when(rs.getString(2)).thenReturn(status);
                when(rs.getObject(3,LocalDate.class)).thenReturn(LocalDate.of(2027,2,10));
                when(rs.getObject(4,LocalDate.class)).thenReturn(LocalDate.of(2027,2,20));
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.query(contains("FROM class_sessions WHERE class_session_id=?"),
                any(RowMapper.class),eq("lesson-1"),eq("class-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn(sv);
                when(rs.getString(2)).thenReturn(lessonStatus);
                when(rs.getObject(3,LocalDate.class)).thenReturn(LocalDate.of(2027,2,11));
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void unauthorizedCannotViewOrCreateLeases() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve(null,request)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class,
                ()->bridge.release("wrong","lease-1")).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void normalCoreWriterModeCannotLeaseAcademicData() {
        when(db.queryForObject(contains("SELECT writer_mode"),eq(String.class)))
            .thenReturn("CORE");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
        verify(db,never()).query(contains("FROM classes"),any(RowMapper.class),any());
    }

    @Test void changedSessionVersionRefusesReservationWithoutInserting() throws Exception {
        when(db.queryForObject(contains("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        classAndLesson(3,8,"READY","PLANNED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_academic_session_leases"),
            any(Object[].class));
    }

    @Test void concurrentActiveAcademicLeaseConflicts() throws Exception {
        when(db.queryForObject(contains("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        classAndLesson(3,7,"RUNNING","PLANNED");
        when(db.queryForObject(contains("SELECT count(*) FROM scheduling_academic_session_leases"),
            eq(Long.class),eq("lesson-1"))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
    }

    @Test void leaseCanBeCreatedAndReleasedWithExactAcademicVersions() throws Exception {
        when(db.queryForObject(contains("SELECT writer_mode"),eq(String.class)))
            .thenReturn("QUIESCED");
        classAndLesson(3,7,"READY","PLANNED");
        when(db.queryForObject(contains("SELECT count(*) FROM scheduling_academic_session_leases"),
            eq(Long.class),eq("lesson-1"))).thenReturn(0L);
        when(db.queryForObject(contains("SELECT expires_at FROM scheduling_academic_session_leases"),
            eq(Timestamp.class),anyString()))
            .thenReturn(Timestamp.from(Instant.parse("2027-02-11T09:05:00Z")));
        var lease=bridge.reserve("trusted-academic-key",request);
        assertEquals("class-1",lease.classId());
        assertEquals("lesson-1",lease.sessionId());
        assertEquals(3,lease.classVersion());
        assertEquals(7,lease.sessionVersion());
        bridge.release("trusted-academic-key",lease.leaseId());
        verify(db).update(contains("DELETE FROM scheduling_academic_session_leases WHERE lease_id=?"),
            eq(lease.leaseId()));
    }

    @Test void expiredLeaseCannotBeRenewed() {
        when(db.query(startsWith("SELECT l.class_id,l.class_session_id"),
            any(RowMapper.class),eq("lease-expired"))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.renew("trusted-academic-key","lease-expired")).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE scheduling_academic_session_leases"),
            any(Object[].class));
    }
}

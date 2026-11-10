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
    private void writerMode(String name,long generation) {
        when(db.queryForObject(startsWith("SELECT writer_mode,writer_generation"),
            any(RowMapper.class))).thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(name);
                when(rs.getLong(2)).thenReturn(generation);
                return mapper.mapRow(rs,0);
            });
    }

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
        writerMode("CORE",2);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
        verify(db,never()).query(contains("FROM classes"),any(RowMapper.class),any());
    }

    @Test void changedSessionVersionRefusesReservationWithoutInserting() throws Exception {
        writerMode("QUIESCED",2);
        classAndLesson(3,8,"READY","PLANNED");
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_academic_session_leases"),
            any(Object[].class));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void existingCommandLease(long classVersion,long sessionVersion) throws Exception {
        when(db.query(contains("SELECT lease_id,expected_class_version"),
                any(RowMapper.class),eq("lesson-1"),eq("command-1")))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("lease-already-created");
                when(rs.getLong(2)).thenReturn(classVersion);
                when(rs.getLong(3)).thenReturn(sessionVersion);
                when(rs.getTimestamp(4)).thenReturn(
                    Timestamp.from(Instant.parse("2027-02-11T09:05:00Z")));
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void lostResponseReplayGetsSameAcademicLeaseWithoutSecondInsert()
            throws Exception {
        writerMode("QUIESCED",2);
        classAndLesson(3,7,"RUNNING","PLANNED");
        existingCommandLease(3,7);
        when(db.queryForObject(startsWith("SELECT expected_writer_generation"),
            eq(Long.class),eq("lease-already-created"))).thenReturn(2L);
        var duplicate=bridge.reserve("trusted-academic-key",request);
        assertEquals("lease-already-created",duplicate.leaseId());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_academic_session_leases"),
            any(Object[].class));
    }

    @Test void commandKeyCannotSilentlyReuseOldAcademicVersions() throws Exception {
        writerMode("QUIESCED",2);
        classAndLesson(3,7,"READY","PLANNED");
        existingCommandLease(3,6);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
    }

    @Test void concurrentActiveAcademicLeaseConflicts() throws Exception {
        writerMode("QUIESCED",2);
        classAndLesson(3,7,"RUNNING","PLANNED");
        when(db.queryForObject(contains("SELECT count(*) FROM scheduling_academic_session_leases"),
            eq(Long.class),eq("lesson-1"))).thenReturn(1L);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
    }

    @Test void leaseCanBeCreatedAndReleasedWithExactAcademicVersions() throws Exception {
        writerMode("QUIESCED",2);
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

    @Test void retiredWriterGenerationCannotReuseAnOldLease() throws Exception {
        writerMode("QUIESCED",4);
        classAndLesson(3,7,"READY","PLANNED");
        existingCommandLease(3,7);
        when(db.queryForObject(startsWith("SELECT expected_writer_generation"),
            eq(Long.class),eq("lease-already-created"))).thenReturn(2L);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.reserve("trusted-academic-key",request)).getStatusCode());
    }

    @Test void expiredOrChangedWriterGenerationCannotRenewAnAcademicLease() {
        writerMode("QUIESCED",4);
        when(db.query(startsWith("SELECT l.class_id,l.class_session_id"),
            any(RowMapper.class),eq("lease-stale"),eq(4L))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.renew("trusted-academic-key","lease-stale")).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE scheduling_academic_session_leases"),
            any(Object[].class));
    }

    @Test void resumedCoreWriterRefusesRenewalWithoutReadingAcademicSession() {
        writerMode("CORE",5);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.renew("trusted-academic-key","lease-1")).getStatusCode());
        verify(db,never()).query(startsWith("SELECT l.class_id"),
            any(RowMapper.class),any(Object[].class));
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    @Test void liveAcademicLeaseRenewsOnlyWithCurrentWriterGeneration() throws Exception {
        writerMode("QUIESCED",4);
        when(db.query(startsWith("SELECT l.class_id,l.class_session_id"),
            any(RowMapper.class),eq("lease-live"),eq(4L))).thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("class-1");
                when(rs.getString(2)).thenReturn("lesson-1");
                when(rs.getLong(3)).thenReturn(3L);
                when(rs.getLong(4)).thenReturn(7L);
                when(rs.getTimestamp(5)).thenReturn(
                    Timestamp.from(Instant.parse("2027-02-11T09:00:00Z")));
                return List.of(mapper.mapRow(rs,0));
            });
        when(db.queryForObject(startsWith("SELECT expires_at"),
            eq(Timestamp.class),eq("lease-live"))).thenReturn(
                Timestamp.from(Instant.parse("2027-02-11T09:06:00Z")));
        var result=bridge.renew("trusted-academic-key","lease-live");
        assertEquals("lease-live",result.leaseId());
        verify(db).update(startsWith("UPDATE scheduling_academic_session_leases"),
            eq("lease-live"));
    }

    @Test void expiredLeaseCannotBeRenewed() {
        writerMode("QUIESCED",2);
        when(db.query(startsWith("SELECT l.class_id,l.class_session_id"),
            any(RowMapper.class),eq("lease-expired"),eq(2L))).thenReturn(List.of());
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->bridge.renew("trusted-academic-key","lease-expired")).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE scheduling_academic_session_leases"),
            any(Object[].class));
    }
}

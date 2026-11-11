package com.example.demo.controller;

import com.example.demo.service.ClassSessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcademicSessionSnapshotBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ClassSessionService sessions=mock(ClassSessionService.class);
    private final Authentication actor=mock(Authentication.class);
    private final AcademicSessionSnapshotBridge bridge=
        new AcademicSessionSnapshotBridge(db,sessions,"private-key");

    @Test void missingServiceKeyRejectsBeforeAnyClassLookup() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revision(null,"class-1",actor)).getStatusCode());
        verifyNoInteractions(db,sessions);
    }

    @Test void revisionRequiresSameLiveClassAclAsExistingPublicGet() {
        when(db.queryForObject(startsWith("SELECT revision FROM academic_session_revision"),
            eq(Long.class))).thenReturn(12L);
        assertEquals(12L,bridge.revision("private-key","class-1",actor).revision());
        verify(sessions).authorizeSessions("class-1",actor);
    }

    @Test void deniedClassAclCannotReadVersionOrSnapshot() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(sessions).authorizeSessions("class-2",actor);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revision("private-key","class-2",actor)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->bridge.snapshot("private-key","class-2",actor)).getStatusCode());
        verifyNoInteractions(db);
    }

    @Test void snapshotCarriesVersionAndExactCoreJsonFields() {
        when(db.queryForObject(startsWith("SELECT revision FROM academic_session_revision"),
            eq(Long.class))).thenReturn(16L);
        when(sessions.sessions("class-1",actor)).thenReturn(
            List.of(Map.of("id","session-1","classId","class-1",
                "date","2027-03-01","startTime","09:00",
                "endTime","11:00","status","PLANNED")));
        var result=bridge.snapshot("private-key","class-1",actor);
        assertEquals(16L,result.revision());
        assertEquals("session-1",result.rows().get(0).get("id"));
        verify(sessions).authorizeSessions("class-1",actor);
        verify(sessions).sessions("class-1",actor);
    }
}

package com.example.demo.controller;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class SchedulingTeacherClaimRevocationBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingTeacherClaimRevocationBridge bridge=
        new SchedulingTeacherClaimRevocationBridge(db,"private-key",
            "http://127.0.0.1:1");
    private final Authentication admin=mock(Authentication.class);
    private static final String REASON=
        "Scheduling rolled back before any immutable teacher event committed";
    private SchedulingTeacherClaimRevocationBridge.Request request() {
        return new SchedulingTeacherClaimRevocationBridge.Request(
            "tc001","orphan-1",REASON);
    }
    private void authenticate() {
        when(admin.getName()).thenReturn("admin");
        when(admin.getPrincipal()).thenReturn("admin");
        when(admin.isAuthenticated()).thenReturn(true);
    }
    private void approved() {
        authenticate();
        when(db.queryForObject(contains("FROM users WHERE"),
            eq(Long.class),eq("admin"))).thenReturn(1L);
        when(db.queryForObject(startsWith("SELECT writer_mode"),
            eq(String.class))).thenReturn("QUIESCED");
    }
    private void claim(String state) throws Exception {
        when(db.query(startsWith("SELECT manager_employee_id,status,revoked_by"),
            any(RowMapper.class),eq("tc001"),eq("orphan-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                var rs=mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn("tc-1");
                when(rs.getString(2)).thenReturn(state);
                return List.of(mapper.mapRow(rs,0));
            });
    }

    @Test void incorrectServiceKeyPreventsAnyIdentityOrDatabaseActivity() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("wrong",request(),admin)).getStatusCode());
        verifyNoInteractions(db,admin);
    }
    @Test void unauthenticatedCallerCannotRevokeAClaim() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",request(),null)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void currentlyInactiveAdminCannotRevokeEvenWithValidJwtPrincipal() {
        authenticate();
        when(db.queryForObject(contains("FROM users WHERE"),
            eq(Long.class),eq("admin"))).thenReturn(0L);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",request(),admin)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void coreWriterMustBeQuiescedBeforeOrphanClaimCleanup() {
        authenticate();
        when(db.queryForObject(contains("FROM users WHERE"),
            eq(Long.class),eq("admin"))).thenReturn(1L);
        when(db.queryForObject(startsWith("SELECT writer_mode"),
            eq(String.class))).thenReturn("CORE");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",request(),admin)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void confirmedTcClaimCannotBeRevokedEvenIfSchedulingServiceIsDown()
            throws Exception {
        approved();
        claim("CONFIRMED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",request(),admin)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void unreachableSchedulingProofDoesNotDeleteOrMutatePendingClaim()
            throws Exception {
        approved();
        claim("PENDING");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",request(),admin)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void claimCleanupRejectsVagueAuditReason() {
        authenticate();
        var vague=new SchedulingTeacherClaimRevocationBridge.Request(
            "tc001","orphan-1","revoke");
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->bridge.revoke("private-key",vague,admin)).getStatusCode());
        verifyNoInteractions(db);
    }
}

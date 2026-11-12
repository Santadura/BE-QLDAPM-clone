package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicRosterClaimBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicRosterClaimBridge bridge=
        new AcademicRosterClaimBridge(db,"private");

    private AcademicRosterClaimBridge.Claim claim() {
        return new AcademicRosterClaimBridge.Claim(
            "admin","request-1","a".repeat(64),
            "TARGET","st-001-ielts",5,6,2);
    }

    @Test void noPrivateKeyNeverInspectsOrMutatesCorePostgres() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->bridge.acquire(null,claim()))
            .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->bridge.release("wrong",
                new AcademicRosterClaimBridge.Release(claim(),
                    "b381ee38-d42f-48e7-bbf8-24198e329503","b".repeat(64))))
            .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->bridge.summary(null))
            .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void badVersionAndEntityCannotCreateCoreClaim() {
        var invalid=new AcademicRosterClaimBridge.Claim(
            "admin","request-1","a".repeat(64),
            "STUDENT","student-1",5,6,2);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->bridge.acquire("private",invalid))
            .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void releaseMustSupplyExactUuidAndOutboxSha256() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->bridge.release("private",
                new AcademicRosterClaimBridge.Release(claim(),
                    "not-a-uuid","b".repeat(64)))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->bridge.release("private",
                new AcademicRosterClaimBridge.Release(claim(),
                    "b381ee38-d42f-48e7-bbf8-24198e329503","bad"))).getStatusCode());
        verifyNoInteractions(db);
    }
}

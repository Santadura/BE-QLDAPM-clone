package com.example.demo.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicCandidateClaimBridgeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicCandidateClaimBridge bridge=
        new AcademicCandidateClaimBridge(db,"private-key");
    private AcademicCandidateClaimBridge.Claim valid() {
        return new AcademicCandidateClaimBridge.Claim("admin","retry-key","a".repeat(64),
            "class-1","session-1",3,2,4,5,6);
    }
    @Test void wrongServiceKeyNeverQueriesClaimsOrFences() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.acquire("wrong",valid())).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.release(null,null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
            ()->bridge.summary("wrong")).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void malformedIdempotencyEvidenceRejectedBeforeSql() {
        var invalid=new AcademicCandidateClaimBridge.Claim("admin","retry-key","z".repeat(64),
            "class-1","session-1",3,2,4,5,6);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->bridge.acquire("private-key",invalid)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void noFrozenCoreRowPreventsNewClaims() {
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->bridge.acquire("private-key",valid())).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void releaseCannotSucceedWithoutExactCandidateProof() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->bridge.release("private-key",new AcademicCandidateClaimBridge.Release(
                valid(),"invalid-uuid","a".repeat(64)))).getStatusCode());
        verifyNoInteractions(db);
    }
}

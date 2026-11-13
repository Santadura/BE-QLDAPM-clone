package com.example.demo.controller;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeCommandClaimBridgeTest {
    private static final String HASH="a".repeat(64);
    private static final String OUTBOX="b".repeat(64);
    private static final String EVENT="00000000-0000-0000-0000-000000000123";
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final AcademicNativeCommandClaimBridge controller=
        new AcademicNativeCommandClaimBridge(db,"secret");

    private AcademicNativeCommandClaimBridge.Claim claim() {
        return new AcademicNativeCommandClaimBridge.Claim(
            "admin","class-1","PLAN","key-1",HASH,4,5,6,7);
    }
    @Test void privateClaimEndpointIsOffUntilExplicitCutover() {
        var annotation=AcademicNativeCommandClaimBridge.class
            .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(annotation);
        assertArrayEquals(new String[]{
            "iig.academic.native-command-claims-enabled"},annotation.name());
        assertEquals("true",annotation.havingValue());
        assertFalse(annotation.matchIfMissing());
    }
    @Test void wrongKeyCannotReadOrReleaseCoreClaim() {
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.acquire(
                "wrong",claim())).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(
            ResponseStatusException.class,()->controller.summary(
                null)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void malformedGenerationOrDigestCannotCreateCoreClaim() {
        var wrong=new AcademicNativeCommandClaimBridge.Claim(
            "admin","class-1","PLAN","key-1","invalid",4,5,6,7);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,()->controller.acquire(
                "secret",wrong)).getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void unrecordedClaimCannotBeReleasedOnUnprovenHttpSuccess() {
        var release=new AcademicNativeCommandClaimBridge.Release(
            claim(),EVENT,OUTBOX);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->controller.release(
                "secret",release)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private void exactRow(String state,String eventId,String outboxHash)
            throws Exception {
        when(db.query(startsWith("SELECT class_id,request_hash,"),
                any(RowMapper.class),eq("admin"),eq("PLAN"),eq("key-1")))
            .thenAnswer(call->{
                RowMapper mapper=call.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("class-1");
                when(rs.getString(2)).thenReturn(HASH);
                when(rs.getLong(3)).thenReturn(4L);
                when(rs.getLong(4)).thenReturn(5L);
                when(rs.getLong(5)).thenReturn(6L);
                when(rs.getLong(6)).thenReturn(7L);
                when(rs.getString(7)).thenReturn(state);
                when(rs.getString(8)).thenReturn(eventId);
                when(rs.getString(9)).thenReturn(outboxHash);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    @Test void exactCommittedReleaseIsIdempotentAndNeverRewritesCoreClaim()
            throws Exception {
        exactRow("RELEASED",EVENT,OUTBOX);
        var receipt=controller.release("secret",
            new AcademicNativeCommandClaimBridge.Release(
                claim(),EVENT,OUTBOX));
        assertEquals("RELEASED",receipt.state());
        assertEquals(EVENT,receipt.eventId());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void changedCommittedDigestRejectsReleaseReplay()
            throws Exception {
        exactRow("RELEASED",EVENT,OUTBOX);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->controller.release(
                "secret",new AcademicNativeCommandClaimBridge.Release(
                    claim(),EVENT,"c".repeat(64)))).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}

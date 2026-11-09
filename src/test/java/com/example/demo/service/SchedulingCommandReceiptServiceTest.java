package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.demo.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Core write receipts must serialize retries before moving command ownership. */
class SchedulingCommandReceiptServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger commits=new AtomicInteger();
    private final AtomicInteger rollbacks=new AtomicInteger();
    private final PlatformTransactionManager tx=new PlatformTransactionManager() {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { commits.incrementAndGet(); }
        @Override public void rollback(TransactionStatus status) { rollbacks.incrementAndGet(); }
    };
    private final SchedulingCommandReceiptService receipts=
        new SchedulingCommandReceiptService(db,json,tx);

    private String hash(Object body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(body)));
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private void stored(String actor,String command,String key,String digest,String reply) {
        when(db.query(startsWith("SELECT request_hash,response_payload::text"),
                any(RowMapper.class),eq(actor),eq(command),eq(key)))
            .thenAnswer(invocation -> {
                RowMapper mapper=invocation.getArgument(1);
                var row=mock(java.sql.ResultSet.class);
                when(row.getString(1)).thenReturn(digest);
                when(row.getString(2)).thenReturn(reply);
                return List.of(mapper.mapRow(row,0));
            });
    }

    @Test void sameActorAndRequestKeyReplaysResultWithoutRunningBusinessWriteTwice()
            throws Exception {
        String actor="tc001",command="POST /api/teaching-schedules",key="request-001";
        var input=Map.of("teacherId","teacher-001","date","2026-12-08");
        var response=Map.<String,Object>of("id","teaching-01","status","ASSIGNED");
        when(db.update(startsWith("INSERT INTO scheduling_command_receipts"),
                eq(actor),eq(command),eq(key),anyString())).thenReturn(1,0);
        when(db.update(startsWith("UPDATE scheduling_command_receipts"),
                anyString(),eq(actor),eq(command),eq(key))).thenReturn(1);
        stored(actor,command,key,hash(input),json.writeValueAsString(response));
        AtomicInteger domainWrites=new AtomicInteger();

        var first=receipts.execute(key,actor,command,input,()->{
            domainWrites.incrementAndGet();
            return response;
        });
        var second=receipts.execute(key,actor,command,input,()->{
            domainWrites.incrementAndGet();
            return Map.of("id","incorrect-duplicate");
        });
        assertEquals(first,second);
        assertEquals(1,domainWrites.get());
        assertEquals(2,commits.get());
        verify(db,times(1)).update(startsWith("UPDATE scheduling_command_receipts"),
            anyString(),eq(actor),eq(command),eq(key));
    }

    @Test void reusedKeyWithDifferentPayloadConflictsInsteadOfChangingAssignment()
            throws Exception {
        String actor="cm001",command="POST /api/staff-schedules",key="same-key";
        when(db.update(startsWith("INSERT INTO scheduling_command_receipts"),
                eq(actor),eq(command),eq(key),anyString())).thenReturn(0);
        stored(actor,command,key,hash(Map.of("employeeId","cs-old")),
            "{\"id\":\"old-schedule\"}");
        AtomicInteger domainWrites=new AtomicInteger();
        ApiException error=assertThrows(ApiException.class,()->receipts.execute(
            key,actor,command,Map.of("employeeId","cs-new"),()->{
                domainWrites.incrementAndGet();return Map.of("id","bad");
            }));
        assertEquals(409,error.getStatus().value());
        assertEquals(0,domainWrites.get());
        assertEquals(1,rollbacks.get());
    }

    @Test void aFailedCommandRollsBackItsReceiptWithTheDomainTransaction() {
        when(db.update(startsWith("INSERT INTO scheduling_command_receipts"),
            eq("tc001"),eq("POST /api/teaching-schedules"),eq("retryable"),
            anyString())).thenReturn(1);
        assertThrows(IllegalStateException.class,()->receipts.execute(
            "retryable","tc001","POST /api/teaching-schedules",
            Map.of("teacherId","teacher-001"),()->{
                throw new IllegalStateException("Core domain write failed");
            }));
        assertEquals(1,rollbacks.get());
        verify(db,never()).update(startsWith("UPDATE scheduling_command_receipts"),
            anyString(),anyString(),anyString(),anyString());
    }

    @Test void cancelledAssignmentStoresJsonNullSo204IsReplayedWithoutSecondAudit()
            throws Exception {
        String actor="cm001",command="DELETE /api/staff-schedules/shift-01",key="cancel-01";
        when(db.update(startsWith("INSERT INTO scheduling_command_receipts"),
            eq(actor),eq(command),eq(key),anyString())).thenReturn(1,0);
        when(db.update(startsWith("UPDATE scheduling_command_receipts"),
            anyString(),eq(actor),eq(command),eq(key))).thenReturn(1);
        stored(actor,command,key,hash(null),"null");
        AtomicInteger domainWrites=new AtomicInteger();
        assertNull(receipts.execute(key,actor,command,null,()->{
            domainWrites.incrementAndGet();return null;
        }));
        assertNull(receipts.execute(key,actor,command,null,()->{
            domainWrites.incrementAndGet();return null;
        }));
        assertEquals(1,domainWrites.get());
    }

    @Test void missingKeyKeepsLegacyFlowAndInvalidKeyDoesNotRunCommands() {
        assertEquals("legacy",receipts.execute(null,"cm001",
            "POST /api/staff-schedules",Map.of(),()->"legacy"));
        assertEquals(0,commits.get());
        var ex=assertThrows(ApiException.class,()->receipts.execute(
            "  wrong key  ","cm001","POST /api/staff-schedules",Map.of(),
            ()->"should never execute"));
        assertEquals(400,ex.getStatus().value());
        verifyNoInteractions(db);
    }
}

package vn.iig.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class AcademicCommandLeaseServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final AcademicCommandLeaseService svc=
        new AcademicCommandLeaseService(db,core,new ObjectMapper());
    private final Instant expires=Instant.now().plusSeconds(260);
    private static final String PAYLOAD="""
        {"operation":"ASSIGN_TEACHER","targetId":null,
         "payload":{"classId":"class-1","sessionId":"lesson-1",
         "classVersion":3,"sessionVersion":7}}""";

    private void staged(String state) throws Exception {
        when(db.query(startsWith("SELECT state,payload::text"),
            any(RowMapper.class),eq("tc001"),eq("command-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(state);
                when(rs.getString(2)).thenReturn(PAYLOAD);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private void claim(String classId,long version) throws Exception {
        claim(classId,version,Instant.now().plusSeconds(240));
    }
    private void claim(String classId,long version,Instant expiry) throws Exception {
        when(db.query(startsWith("SELECT class_id,session_id,class_version"),
            any(RowMapper.class),eq("tc001"),eq("command-1")))
            .thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(classId);
                when(rs.getString(2)).thenReturn("lesson-1");
                when(rs.getLong(3)).thenReturn(version);
                when(rs.getLong(4)).thenReturn(7L);
                when(rs.getString(5)).thenReturn("lease-1");
                when(rs.getTimestamp(6)).thenReturn(Timestamp.from(expiry));
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private SchedulingCoreClient.AcademicLease live() {
        return new SchedulingCoreClient.AcademicLease(
            "lease-1","class-1","lesson-1",3,7,expires);
    }

    @Test void stagedTeacherCommandReservesAndPersistsSameCoreLeaseIdentity() throws Exception {
        staged("STAGED");
        String key=AcademicCommandLeaseService.scopedCoreCommandKey(
            "tc001","ASSIGN_TEACHER","command-1");
        assertTrue(key.matches("[0-9a-f]{64}"));
        when(core.reserveAcademicSession("class-1","lesson-1",3,7,key))
            .thenReturn(live());
        var receipt=svc.acquire("tc001","command-1");
        assertEquals("lease-1",receipt.leaseId());
        assertEquals(3L,receipt.classVersion());
        verify(db).update(startsWith("INSERT INTO scheduling_academic_command_leases"),
            eq("tc001"),eq("command-1"),eq("class-1"),eq("lesson-1"),
            eq(3L),eq(7L),eq("lease-1"),any(java.sql.Timestamp.class));
    }

    @Test void retryOfAlreadyBoundCommandMustRenewSameLeaseNotCreateSecond()
            throws Exception {
        staged("STAGED");claim("class-1",3);
        when(core.renewAcademicSession("lease-1")).thenReturn(live());
        when(db.update(startsWith("UPDATE scheduling_academic_command_leases"),
            any(Timestamp.class),eq("tc001"),eq("command-1"),eq("lease-1")))
            .thenReturn(1);
        var receipt=svc.acquire("tc001","command-1");
        assertEquals("lease-1",receipt.leaseId());
        verify(core,never()).reserveAcademicSession(anyString(),anyString(),
            anyLong(),anyLong(),anyString());
        verify(db).update(startsWith("UPDATE scheduling_academic_command_leases"),
            any(java.sql.Timestamp.class),eq("tc001"),eq("command-1"),
            eq("lease-1"));
    }

    @Test void explicitRenewRequiresMatchingActorAndSameVersionedLease()
            throws Exception {
        staged("STAGED");claim("class-1",3);
        when(core.renewAcademicSession("lease-1")).thenReturn(live());
        when(db.update(startsWith("UPDATE scheduling_academic_command_leases"),
            any(Timestamp.class),eq("tc001"),eq("command-1"),eq("lease-1")))
            .thenReturn(1);
        assertEquals("lease-1",svc.renew("tc001","command-1").leaseId());
        verify(core).renewAcademicSession("lease-1");
    }

    @Test void retiredOrChangedAcademicLeaseDoesNotUpdateLocalReceipt()
            throws Exception {
        staged("STAGED");claim("class-1",2);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.renew("tc001","command-1")).getStatusCode());
        verify(core,never()).renewAcademicSession(anyString());
    }

    @Test void nonStagedTeacherCommandCannotAcquireOrRenew() throws Exception {
        staged("PENDING_REFERENCE");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
        verifyNoInteractions(core);
    }

    @Test void expiredClaimIsReacquiredWithTheSameScopedCoreKey() throws Exception {
        staged("STAGED");
        claim("class-1",3,Instant.now().minusSeconds(30));
        String key=AcademicCommandLeaseService.scopedCoreCommandKey(
            "tc001","ASSIGN_TEACHER","command-1");
        var successor=new SchedulingCoreClient.AcademicLease("lease-2",
            "class-1","lesson-1",3,7,expires);
        when(core.reserveAcademicSession("class-1","lesson-1",3,7,key))
            .thenReturn(successor);
        when(db.update(startsWith("UPDATE scheduling_academic_command_leases"),
            eq("lease-2"),any(Timestamp.class),eq("tc001"),eq("command-1"),
            eq("lease-1"))).thenReturn(1);
        var recovered=svc.acquire("tc001","command-1");
        assertEquals("lease-2",recovered.leaseId());
        verify(core,never()).renewAcademicSession("lease-1");
    }

    @Test void expiredClaimCannotBeRecoveredIfAcademicSessionChanged() throws Exception {
        staged("STAGED");
        claim("class-1",3,Instant.now().minusSeconds(30));
        when(core.reserveAcademicSession(anyString(),anyString(),anyLong(),anyLong(),
            anyString())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "writer generation changed"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
        verify(db,never()).update(startsWith("UPDATE scheduling_academic_command_leases"),
            any(Object[].class));
    }

    @Test void failedRecoveryCursorWriteNeverReportsAcademicLeaseSuccess() throws Exception {
        staged("STAGED");
        claim("class-1",3,Instant.now().minusSeconds(30));
        when(core.reserveAcademicSession(anyString(),anyString(),anyLong(),anyLong(),
            anyString())).thenReturn(new SchedulingCoreClient.AcademicLease(
                "lease-2","class-1","lesson-1",3,7,expires));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
    }

    @Test void alreadyCommittedTeacherCommandCannotReacquireExpiredLease() throws Exception {
        staged("COMMITTED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
        verifyNoInteractions(core);
    }

    @Test void pendingReferenceMayOnlyRenewItsOriginalUnexpiredLease() throws Exception {
        staged("PENDING_REFERENCE");
        claim("class-1",3);
        when(core.renewAcademicSession("lease-1")).thenReturn(live());
        when(db.update(startsWith("UPDATE scheduling_academic_command_leases"),
            any(Timestamp.class),eq("tc001"),eq("command-1"),eq("lease-1")))
            .thenReturn(1);
        var response=svc.renewForReference("tc001","command-1");
        assertEquals("lease-1",response.leaseId());
        verify(core).renewAcademicSession("lease-1");
        verify(core,never()).reserveAcademicSession(
            anyString(),anyString(),anyLong(),anyLong(),anyString());
    }

    @Test void expiredPendingLeaseCannotReacquireAnotherLesson() throws Exception {
        staged("PENDING_REFERENCE");
        claim("class-1",3,Instant.now().minusSeconds(25));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.renewForReference("tc001","command-1")).getStatusCode());
        verifyNoInteractions(core);
    }

    @Test void stagedTeacherCommandCannotPretendToBePendingCoreDelivery()
            throws Exception {
        staged("STAGED");
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.renewForReference("tc001","command-1")).getStatusCode());
        verifyNoInteractions(core);
    }

    @Test void rejectedCommandCanReleaseButNeverAcquireOrRenew() throws Exception {
        staged("REJECTED");claim("class-1",3);
        svc.release("tc001","command-1");
        verify(core).releaseAcademicSession("lease-1");
        verify(db).update(startsWith("DELETE FROM scheduling_academic_command_leases"),
            eq("tc001"),eq("command-1"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
    }

    @Test void failedCoreLeaseDoesNotSaveFalseLocalSuccess() throws Exception {
        staged("STAGED");
        when(core.reserveAcademicSession(anyString(),anyString(),anyLong(),
            anyLong(),anyString()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"writer active"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.acquire("tc001","command-1")).getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO scheduling_academic_command_leases"),
            any(Object[].class));
    }
}

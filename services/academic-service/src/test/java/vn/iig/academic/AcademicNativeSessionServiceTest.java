package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicNativeSessionServiceTest {
    final AcademicCoreClient core=mock(AcademicCoreClient.class);
    final AcademicFullCutoverReadiness readiness=
        mock(AcademicFullCutoverReadiness.class);
    final AcademicNativeSessionRepository ledger=
        mock(AcademicNativeSessionRepository.class);
    final AcademicNativeCommandClaimClient claims=
        mock(AcademicNativeCommandClaimClient.class);
    final AcademicNativeSessionService service=
        new AcademicNativeSessionService(core,readiness,ledger,claims);

    @Test void liveNonAdminCannotActivateIndependentWriter() {
        when(core.currentPrincipal("Bearer cs")).thenReturn(
            new AcademicCoreClient.Principal("cs001","ROLE_CS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(
            ResponseStatusException.class,
            ()->service.activate("class-1","Bearer cs")).getStatusCode());
        verifyNoInteractions(readiness,ledger);
    }
    @Test void nativeMutationRequiresAStableIdempotencyKey() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",
                new ObjectMapper().createArrayNode(),"Bearer admin",null))
            .getStatusCode());
        verifyNoInteractions(readiness,ledger);
    }
    @Test void independentPreflightOutageFailsClosedBeforeAnyLocalWrite() {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        when(readiness.verifyStaging()).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Downstream Scheduling unreachable"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->service.execute("PLAN","class-1",
                new ObjectMapper().createArrayNode(),"Bearer admin","key-1"))
            .getStatusCode());
        verify(ledger).commandDigest(eq("PLAN"),eq("class-1"),any());
        verify(ledger,never()).execute(anyString(),anyString(),anyString(),
            anyString(),any(),anyLong(),anyLong());
        verifyNoInteractions(claims);
    }

    private final ObjectMapper mapper=new ObjectMapper();
    private static final String HASH="a".repeat(64);
    private static final String EVENT="00000000-0000-0000-0000-000000000123";
    private static final String OUTBOX="b".repeat(64);

    private com.fasterxml.jackson.databind.JsonNode nativeReady() throws Exception {
        when(core.currentPrincipal("Bearer admin")).thenReturn(
            new AcademicCoreClient.Principal("admin","ROLE_ADMIN"));
        var classes=mapper.readTree(
            "[{\"classId\":\"class-1\",\"status\":\"RUNNING\",\"startDate\":\"2027-02-01\",\"endDate\":\"2027-02-28\"}]");
        when(core.planningExport()).thenReturn(
            new AcademicCoreClient.PlanningExport(7,classes));
        when(readiness.verifyStaging()).thenReturn(
            new AcademicFullCutoverReadiness.Evidence(4,7,8,9,
                0,0,0,0,0,0,null));
        var body=mapper.createArrayNode();
        when(ledger.commandDigest(eq("PLAN"),eq("class-1"),same(body)))
            .thenReturn(HASH);
        when(claims.acquire(any())).thenAnswer(call-> {
            AcademicNativeCommandClaimClient.Claim c=call.getArgument(0);
            return new AcademicNativeCommandClaimClient.Receipt(
                "OPEN",c.actorUsername(),c.classId(),c.operation(),
                c.requestKey(),null,null);
        });
        return body;
    }

    @Test void acquiresCoreClaimThenCommitsLocalEventBeforeRelease()
            throws Exception {
        var payload=nativeReady();
        var rows=mapper.createArrayNode();
        when(ledger.execute(eq("admin"),eq("class-1"),eq("PLAN"),
            eq("key-1"),same(payload),eq(7L),eq(4L))).thenReturn(rows);
        when(ledger.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenReturn(new AcademicNativeSessionRepository.CommittedEvidence(
                HASH,EVENT,OUTBOX,rows));
        assertSame(rows,service.execute("PLAN","class-1",payload,"Bearer admin","key-1"));
        var order=inOrder(claims,ledger);
        order.verify(claims).acquire(any());
        order.verify(ledger).execute(eq("admin"),eq("class-1"),eq("PLAN"),
            eq("key-1"),same(payload),eq(7L),eq(4L));
        order.verify(ledger).committedEvidence("admin","class-1","PLAN","key-1",HASH);
        order.verify(claims).release(argThat(x->
            EVENT.equals(x.eventId()) && OUTBOX.equals(x.outboxHash()) &&
            HASH.equals(x.claim().requestHash()) &&
            x.claim().rosterGeneration()==4 && x.claim().sessionGeneration()==7 &&
            x.claim().attendanceGeneration()==8 &&
            x.claim().schedulingGeneration()==9));
    }

    @Test void exactCoreReleaseReplayNeverStagesSecondNativeMutation()
            throws Exception {
        var payload=nativeReady();
        var saved=mapper.createArrayNode();
        doAnswer(call-> {
            AcademicNativeCommandClaimClient.Claim c=call.getArgument(0);
            return new AcademicNativeCommandClaimClient.Receipt(
                "RELEASED",c.actorUsername(),c.classId(),c.operation(),
                c.requestKey(),EVENT,OUTBOX);
        }).when(claims).acquire(any());
        when(ledger.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenReturn(new AcademicNativeSessionRepository.CommittedEvidence(
                HASH,EVENT,OUTBOX,saved));
        assertSame(saved,service.execute("PLAN","class-1",payload,"Bearer admin","key-1"));
        verify(ledger,never()).execute(anyString(),anyString(),anyString(),
            anyString(),any(),anyLong(),anyLong());
        verify(claims,never()).release(any());
    }

    @Test void lostCoreAcquireAckNeverStartsIndependentAcademicMutation()
            throws Exception {
        var payload=nativeReady();
        doThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Claim network reply uncertain"))
            .when(claims).acquire(any());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.execute("PLAN",
                "class-1",payload,"Bearer admin","key-1")).getStatusCode());
        verify(ledger,never()).execute(anyString(),anyString(),anyString(),
            anyString(),any(),anyLong(),anyLong());
        verify(claims,never()).release(any());
    }

    @Test void lostCoreReleaseAckDoesNotUndoCommittedAcademicOutbox()
            throws Exception {
        var payload=nativeReady();
        var rows=mapper.createArrayNode();
        when(ledger.execute(anyString(),anyString(),anyString(),anyString(),
            any(),anyLong(),anyLong())).thenReturn(rows);
        when(ledger.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenReturn(new AcademicNativeSessionRepository.CommittedEvidence(
                HASH,EVENT,OUTBOX,rows));
        when(claims.release(any())).thenThrow(new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Release response ambiguous"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.execute("PLAN",
                "class-1",payload,"Bearer admin","key-1")).getStatusCode());
        verify(ledger).execute(anyString(),anyString(),anyString(),anyString(),
            any(),anyLong(),anyLong());
        verify(claims).release(any());
    }

    @Test void failedPostCommitParityLeavesCoreHandbackBlocked()
            throws Exception {
        var payload=nativeReady();
        var rows=mapper.createArrayNode();
        when(ledger.execute(anyString(),anyString(),anyString(),anyString(),
            any(),anyLong(),anyLong())).thenReturn(rows);
        when(ledger.committedEvidence("admin","class-1","PLAN","key-1",HASH))
            .thenReturn(new AcademicNativeSessionRepository.CommittedEvidence(
                HASH,EVENT,OUTBOX,rows));
        when(readiness.verifyStaging()).thenReturn(
            new AcademicFullCutoverReadiness.Evidence(4,7,8,9,0,0,0,0,0,0,null))
            .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Payroll history disappeared"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,()->service.execute("PLAN",
                "class-1",payload,"Bearer admin","key-1")).getStatusCode());
        verify(claims,never()).release(any());
    }
}

package vn.iig.scheduling;

import java.sql.ResultSet;
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
class TeacherClaimReconciliationServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final SchedulingCoreClient core=mock(SchedulingCoreClient.class);
    private final TeacherClaimReconciliationService svc=
        new TeacherClaimReconciliationService(db,core);
    private static final String ACTOR="tc001",KEY="cmd-1",HASH="a".repeat(64);
    private static final String PAYLOAD="""
        {"id":"teach-1","teacherId":"teacher-1","classId":"class-1",
        "date":"2027-02-11","startTime":"09:00","endTime":"11:00",
        "status":"ASSIGNED","assignedBy":"tc-1"}""";
    private void local(String state,String eventId,boolean delivered) throws Exception {
        when(db.query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
            any(RowMapper.class),eq(ACTOR),eq(KEY)))
            .thenReturn(state==null?List.of():List.of(state));
        when(db.query(startsWith("SELECT event_id,schedule_id,payload_hash"),
            any(RowMapper.class),eq(ACTOR),eq(KEY)))
            .thenAnswer(inv->{
                if(eventId==null)return List.of();
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn(eventId);
                when(rs.getString(2)).thenReturn("teach-1");
                when(rs.getString(3)).thenReturn(HASH);
                when(rs.getLong(4)).thenReturn(1L);
                when(rs.getBoolean(5)).thenReturn(delivered);
                when(rs.getString(6)).thenReturn(PAYLOAD);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private void coreClaim(boolean found,String status,String eventId,boolean live) {
        when(core.teacherClaimEvidence(ACTOR,KEY)).thenReturn(
            new SchedulingCoreClient.TeacherClaimEvidence(found,ACTOR,KEY,
                found?"tc-1":null,status,eventId==null?0:1,
                eventId,eventId==null?null:"teach-1",
                eventId==null?null:HASH,eventId==null?0:1,live,
                live?"teacher-1":null,live?"class-1":null,
                live?java.time.LocalDate.of(2027,2,11):null,
                live?java.time.LocalTime.of(9,0):null,
                live?java.time.LocalTime.of(11,0):null,
                live?"ASSIGNED":null,live?"tc-1":null));
    }

    private void abandonmentState(String state,long outgoing) throws Exception {
        when(db.query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
            any(RowMapper.class),eq(ACTOR),eq(KEY))).thenReturn(List.of(state));
        when(db.queryForObject(startsWith("SELECT count(*) FROM scheduling_assignment_domain_outbox"),
            eq(Long.class),eq(ACTOR),eq(KEY))).thenReturn(outgoing);
        when(db.query(startsWith("SELECT admin_username,reason"),
            any(RowMapper.class),eq(ACTOR),eq(KEY))).thenAnswer(inv->{
                RowMapper mapper=inv.getArgument(1);
                ResultSet rs=mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("admin");
                when(rs.getString(2)).thenReturn(REASON);
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private static final String REASON=
        "No Scheduling source event was committed before Core claimed authority";

    @Test void stagedCommandMayBeRejectedOnlyWithZeroOutboxAndImmutableAudit()
        throws Exception {
        abandonmentState("STAGED",0);
        when(db.update(startsWith("UPDATE scheduling_assignment_command_inbox"),
            eq(ACTOR),eq(KEY))).thenReturn(1);
        var receipt=svc.abandonLocally(ACTOR,KEY,"admin",REASON);
        assertEquals("REJECTED",receipt.commandState());
        var order=inOrder(db);
        order.verify(db).query(startsWith("SELECT state FROM scheduling_assignment_command_inbox"),
            any(RowMapper.class),eq(ACTOR),eq(KEY));
        order.verify(db).queryForObject(startsWith("SELECT count(*) FROM scheduling_assignment_domain_outbox"),
            eq(Long.class),eq(ACTOR),eq(KEY));
        order.verify(db).update(startsWith("UPDATE scheduling_assignment_command_inbox"),
            eq(ACTOR),eq(KEY));
        order.verify(db).update(startsWith("INSERT INTO scheduling_teacher_claim_abandon_audit"),
            eq(ACTOR),eq(KEY),eq("admin"),eq(REASON));
        verifyNoInteractions(core);
    }

    @Test void alreadyRejectedSameAuditCanRetryCoreCallWithoutReopeningCommand()
        throws Exception {
        abandonmentState("REJECTED",0);
        assertEquals("REJECTED",
            svc.abandonLocally(ACTOR,KEY,"admin",REASON).commandState());
        verify(db,never()).update(startsWith("UPDATE scheduling_assignment_command_inbox"),
            any(Object[].class));
        verifyNoInteractions(core);
    }
    @Test void teacherCommandWithCommittedEventCannotBeWithdrawn() throws Exception {
        abandonmentState("STAGED",1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.abandonLocally(ACTOR,KEY,"admin",REASON)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void pendingReferenceCannotBeWithdrawnAsIfItNeverExecuted()
        throws Exception {
        abandonmentState("PENDING_REFERENCE",1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.abandonLocally(ACTOR,KEY,"admin",REASON)).getStatusCode());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void immutableAuditPreventsDifferentAdminOrReasonRetry()
        throws Exception {
        abandonmentState("REJECTED",0);
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->svc.abandonLocally(ACTOR,KEY,"different-admin",REASON))
            .getStatusCode());
    }
    @Test void revokedOrphanClaimIsVisibleButNeverAutomaticallyDeleted()
        throws Exception {
        local("REJECTED",null,false);
        coreClaim(true,"REVOKED",null,false);
        assertEquals("ORPHAN_CLAIM_REVOKED",svc.diagnose(ACTOR,KEY).state());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void identifiesOrphanClaimAfterCoreAdmitsButSchedulingRollback() throws Exception {
        local("STAGED",null,false);
        coreClaim(true,"PENDING",null,false);
        var diagnosis=svc.diagnose(ACTOR,KEY);
        assertEquals("ORPHAN_CORE_CLAIM",diagnosis.state());
        assertEquals("PENDING",diagnosis.coreClaimState());
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(core).requireCoreWriterQuiesced();
    }
    @Test void pendingReferenceWithoutCoreReceiptIsNotSafeToHandback() throws Exception {
        local("PENDING_REFERENCE","event-1",false);
        coreClaim(true,"PENDING",null,false);
        assertEquals("AWAITING_CORE_REFERENCE",svc.diagnose(ACTOR,KEY).state());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void ambiguousResponseWithConfirmedCoreReceiptRequiresAckRecovery() throws Exception {
        local("COMPENSATING","event-1",false);
        coreClaim(true,"CONFIRMED","event-1",true);
        assertEquals("CORE_COMMITTED_LOCAL_ACK_MISSING",svc.diagnose(ACTOR,KEY).state());
    }
    @Test void completedScheduleAndCoreClaimMustBothMatch() throws Exception {
        local("COMMITTED","event-1",true);
        coreClaim(true,"CONFIRMED","event-1",true);
        assertEquals("CONSISTENT_COMMITTED",svc.diagnose(ACTOR,KEY).state());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void changedCoreEventIdBlocksAutomaticRecoveryClassification() throws Exception {
        local("COMPENSATING","event-1",false);
        coreClaim(true,"CONFIRMED","unexpected-event",true);
        assertEquals("MANUAL_REVIEW_REQUIRED",svc.diagnose(ACTOR,KEY).state());
    }
    @Test void changedCoreTeacherCannotBeMistakenForConsistentCommit() throws Exception {
        local("COMMITTED","event-1",true);
        var changed=new SchedulingCoreClient.TeacherClaimEvidence(
            true,ACTOR,KEY,"tc-1","CONFIRMED",1,"event-1","teach-1",
            HASH,1,true,"teacher-2","class-1",
            java.time.LocalDate.of(2027,2,11),
            java.time.LocalTime.of(9,0),java.time.LocalTime.of(11,0),
            "ASSIGNED","tc-1");
        when(core.teacherClaimEvidence(ACTOR,KEY)).thenReturn(changed);
        assertEquals("MANUAL_REVIEW_REQUIRED",svc.diagnose(ACTOR,KEY).state());
    }

    @Test void changedCoreShiftCannotBeMistakenForConsistentCommit() throws Exception {
        local("COMMITTED","event-1",true);
        var changed=new SchedulingCoreClient.TeacherClaimEvidence(
            true,ACTOR,KEY,"tc-1","CONFIRMED",1,"event-1","teach-1",
            HASH,1,true,"teacher-1","class-1",
            java.time.LocalDate.of(2027,2,11),
            java.time.LocalTime.of(10,0),java.time.LocalTime.of(12,0),
            "ASSIGNED","tc-1");
        when(core.teacherClaimEvidence(ACTOR,KEY)).thenReturn(changed);
        assertEquals("MANUAL_REVIEW_REQUIRED",svc.diagnose(ACTOR,KEY).state());
    }

    @Test void missingCoreEvidenceMustNotBeCalledAConfirmedAssignment() throws Exception {
        local("STAGED",null,false);
        coreClaim(false,null,null,false);
        assertEquals("NO_CORE_CLAIM",svc.diagnose(ACTOR,KEY).state());
    }
    @Test void classificationRefusesWhenCoreNotQuiesced() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"writer active"))
            .when(core).requireCoreWriterQuiesced();
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,()->svc.diagnose(ACTOR,KEY))
            .getStatusCode());
        verifyNoInteractions(db);
    }
    @Test void cannotUseUnvalidatedUserInputAsClaimLookup() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->svc.diagnose("tc001;DROP TABLE x","cmd-1")).getStatusCode());
        verifyNoInteractions(db,core);
    }
}

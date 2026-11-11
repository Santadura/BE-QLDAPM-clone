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
                return List.of(mapper.mapRow(rs,0));
            });
    }
    private void coreClaim(boolean found,String status,String eventId,boolean live) {
        when(core.teacherClaimEvidence(ACTOR,KEY)).thenReturn(
            new SchedulingCoreClient.TeacherClaimEvidence(found,ACTOR,KEY,
                found?"tc-1":null,status,eventId==null?0:1,
                eventId,eventId==null?null:"teach-1",
                eventId==null?null:HASH,eventId==null?0:1,live));
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

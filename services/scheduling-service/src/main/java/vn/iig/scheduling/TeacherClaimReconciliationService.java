package vn.iig.scheduling;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * READ-ONLY distributed diagnosis for the gap between Core writer claim
 * acquisition and local Scheduling commit. This NEVER issues a claim release,
 * reference acknowledgement, source tombstone or handback. It is an
 * operator snapshot, not an atomic cross-PostgreSQL recovery lease.
 */
@Service
public class TeacherClaimReconciliationService {
    public record Diagnosis(String actorUsername,String requestKey,
        String state,String localCommandState,String coreClaimState,
        String coreManagerEmployeeId,String localEventId,String localScheduleId,
        boolean localEventDelivered,boolean coreTeacherRowExists,
        String guidance) {}
    private record LocalEvent(String eventId,String scheduleId,String hash,
                              long revision,boolean delivered) {}
    private final JdbcTemplate db;
    private final SchedulingCoreClient core;

    public TeacherClaimReconciliationService(JdbcTemplate db,SchedulingCoreClient core) {
        this.db=db;this.core=core;
    }

    public Diagnosis diagnose(String actor,String key) {
        if(actor==null || !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact TC actor and teacher command key required");
        core.requireCoreWriterQuiesced();
        List<String> states=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            (rs,i)->rs.getString(1),actor,key);
        List<LocalEvent> events=db.query(
            "SELECT event_id,schedule_id,payload_hash,revision,delivered_at IS NOT NULL " +
            "FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=? " +
            "AND source_type='TEACHING' ORDER BY created_at,event_id LIMIT 2",
            (rs,i)->new LocalEvent(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getBoolean(5)),actor,key);
        var remote=core.teacherClaimEvidence(actor,key);
        String local=states.size()==1?states.get(0):null;
        LocalEvent event=events.size()==1?events.get(0):null;
        boolean same=event!=null && remote.referenceEventCount()==1 &&
            event.eventId().equals(remote.referenceEventId()) &&
            event.scheduleId().equals(remote.referenceScheduleId()) &&
            event.hash().equals(remote.referencePayloadHash()) &&
            event.revision()==remote.referenceRevision() &&
            event.revision()==1;
        boolean confirmed=remote.found() &&
            "CONFIRMED".equals(remote.claimStatus()) &&
            remote.coreTeacherRowExists() && same;
        String state;
        String guidance;
        if(states.size()>1 || events.size()>1 ||
                remote.referenceEventCount()>1 ||
                (remote.found() && (remote.managerEmployeeId()==null ||
                    remote.managerEmployeeId().isBlank()))) {
            state="CONFLICTING_EVIDENCE";
            guidance="Keep writer quiesced; investigate duplicate or incomplete DB evidence";
        } else if(!remote.found() && event==null &&
                (local==null || "STAGED".equals(local) || "REJECTED".equals(local))) {
            state="NO_CORE_CLAIM";
            guidance="No acquired TC claim; this snapshot alone never authorizes handback";
        } else if(remote.found() && "PENDING".equals(remote.claimStatus()) &&
                event==null && "STAGED".equals(local) &&
                remote.referenceEventCount()==0) {
            state="ORPHAN_CORE_CLAIM";
            guidance="Core claim exists without local TEACHING event; freeze writes and audit before any claim cleanup";
        } else if(remote.found() && "PENDING".equals(remote.claimStatus()) &&
                event!=null && !event.delivered() &&
                "PENDING_REFERENCE".equals(local) &&
                remote.referenceEventCount()==0) {
            state="AWAITING_CORE_REFERENCE";
            guidance="Never release claim; inspect relay failures and Academic lease before retry";
        } else if(confirmed && !event.delivered() &&
                ("PENDING_REFERENCE".equals(local) || "COMPENSATING".equals(local))) {
            state="CORE_COMMITTED_LOCAL_ACK_MISSING";
            guidance="Use receipt-verified reconciliation; do not reassign or delete Core reference";
        } else if(confirmed && event.delivered() && "COMMITTED".equals(local)) {
            state="CONSISTENT_COMMITTED";
            guidance="Immutable receipt and local state agree; do not mutate confirmed claim";
        } else {
            state="MANUAL_REVIEW_REQUIRED";
            guidance="Core/Scheduling claim, state or reference disagrees; block handback and verify original immutable receipts";
        }
        return new Diagnosis(actor,key,state,local,
            remote.found()?remote.claimStatus():null,
            remote.managerEmployeeId(),event==null?null:event.eventId(),
            event==null?null:event.scheduleId(),
            event!=null && event.delivered(),remote.coreTeacherRowExists(),
            guidance);
    }
}

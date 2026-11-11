package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
                              long revision,boolean delivered,String payloadJson) {}
    private static final ObjectMapper JSON=new ObjectMapper();

    private boolean exactCurrentTeacher(LocalEvent local,
                                        SchedulingCoreClient.TeacherClaimEvidence core) {
        if(local==null || !core.coreTeacherRowExists() ||
                core.coreDate()==null || core.coreStartTime()==null ||
                core.coreEndTime()==null || core.coreTeacherId()==null ||
                core.coreClassId()==null || core.coreAssignedBy()==null)
            return false;
        try {
            JsonNode p=JSON.readTree(local.payloadJson());
            return p!=null && p.isObject() &&
                local.scheduleId().equals(p.path("id").asText()) &&
                core.coreTeacherId().equals(p.path("teacherId").asText()) &&
                core.coreClassId().equals(p.path("classId").asText()) &&
                core.coreDate().equals(LocalDate.parse(p.path("date").asText())) &&
                core.coreStartTime().equals(LocalTime.parse(p.path("startTime").asText())) &&
                core.coreEndTime().equals(LocalTime.parse(p.path("endTime").asText())) &&
                "ASSIGNED".equals(core.coreStatus()) &&
                "ASSIGNED".equals(p.path("status").asText()) &&
                core.coreAssignedBy().equals(p.path("assignedBy").asText()) &&
                core.coreAssignedBy().equals(core.managerEmployeeId());
        } catch(Exception invalidEvidence) {
            return false;
        }
    }
    private final JdbcTemplate db;
    public record LocalAbandonReceipt(String actorUsername,String requestKey,
        String commandState,String adminUsername,String reason) {}
    public record AbandonProof(String actorUsername,String requestKey,
        String state,long sourceEventCount,long immutableAuditCount) {}
    private record Audit(String administrator,String reason) {}
    private final SchedulingCoreClient core;

    public TeacherClaimReconciliationService(JdbcTemplate db,SchedulingCoreClient core) {
        this.db=db;this.core=core;
    }

    private void validate(String actor,String key) {
        if(actor==null || !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact TC actor and teacher command key required");
    }

    /**
     * Local first stage of safe claim abandonment. It serializes against
     * TeacherAssignmentExecutor's FOR UPDATE of this SAME command inbox.
     * REJECTED is permanent and its DB state trigger forbids rejection if
     * any immutable outbox event ever committed. The follow-up Core call
     * is a distinct transaction; on failure the claim remains PENDING,
     * preventing unsafe Core handback until an explicit retry.
     */
    @Transactional
    public LocalAbandonReceipt abandonLocally(String actor,String key,
                                               String admin,String reason) {
        validate(actor,key);
        if(admin==null || !admin.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                reason==null || reason.trim().length()<20 ||
                reason.length()>300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Administrator and specific 20-300 character audit reason required");
        List<String> states=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=? FOR UPDATE",
            (rs,i)->rs.getString(1),actor,key);
        if(states.size()!=1 || (!"STAGED".equals(states.get(0)) &&
                                 !"REJECTED".equals(states.get(0))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a non-executed or previously rejected teacher command can be abandoned");
        Long outgoing=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            Long.class,actor,key);
        if(outgoing==null || outgoing!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher source events prevent orphan-claim abandonment");
        if("STAGED".equals(states.get(0))) {
            int changed=db.update(
                "UPDATE scheduling_assignment_command_inbox " +
                "SET state='REJECTED',updated_at=NOW() " +
                "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
                "AND request_key=? AND state='STAGED'",
                actor,key);
            if(changed!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Teacher command changed during irreversible rejection");
        }
        db.update("INSERT INTO scheduling_teacher_claim_abandon_audit " +
            "(actor_username,operation,request_key,admin_username,reason) " +
            "VALUES(?,'ASSIGN_TEACHER',?,?,?) ON CONFLICT DO NOTHING",
            actor,key,admin,reason.trim());
        List<Audit> audits=db.query(
            "SELECT admin_username,reason FROM scheduling_teacher_claim_abandon_audit " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            (rs,i)->new Audit(rs.getString(1),rs.getString(2)),actor,key);
        if(audits.size()!=1 || !admin.equals(audits.get(0).administrator()) ||
                !reason.trim().equals(audits.get(0).reason()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher command already abandoned under a different immutable audit");
        return new LocalAbandonReceipt(actor,key,"REJECTED",admin,reason.trim());
    }

    /**
     * Core checks this exact immutable Scheduling state before revoking an
     * orphan writer claim. It is a read only proof; no request body can
     * forge REJECTED or omit an existing local source event.
     */
    @Transactional(readOnly=true)
    public AbandonProof abandonmentProof(String actor,String key) {
        validate(actor,key);
        List<String> state=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            (rs,i)->rs.getString(1),actor,key);
        Long eventCount=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            Long.class,actor,key);
        Long auditCount=db.queryForObject(
            "SELECT count(*) FROM scheduling_teacher_claim_abandon_audit " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            Long.class,actor,key);
        return new AbandonProof(actor,key,state.size()==1?state.get(0):null,
            eventCount==null?-1:eventCount,auditCount==null?-1:auditCount);
    }

    public Diagnosis diagnose(String actor,String key) {
        validate(actor,key);
        core.requireCoreWriterQuiesced();
        List<String> states=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            (rs,i)->rs.getString(1),actor,key);
        List<LocalEvent> events=db.query(
            "SELECT event_id,schedule_id,payload_hash,revision,delivered_at IS NOT NULL,payload::text " +
            "FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=? " +
            "AND source_type='TEACHING' ORDER BY created_at,event_id LIMIT 2",
            (rs,i)->new LocalEvent(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getBoolean(5),rs.getString(6)),actor,key);
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
            same && exactCurrentTeacher(event,remote);
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
        } else if(remote.found() && "REVOKED".equals(remote.claimStatus()) &&
                event==null && "REJECTED".equals(local) &&
                remote.referenceEventCount()==0) {
            state="ORPHAN_CLAIM_REVOKED";
            guidance="Core claim is durably revoked; verify all other claims, references and leases before any writer handback";
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

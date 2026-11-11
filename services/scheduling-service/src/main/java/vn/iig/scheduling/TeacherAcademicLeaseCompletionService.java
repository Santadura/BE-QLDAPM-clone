package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Close a generation-bound teacher Academic lease only AFTER a durable
 * Scheduling COMMITTED command and an exact immutable Core TEACHING receipt.
 *
 * The remote Core DELETE is idempotent. If it commits but HTTP fails, the
 * Scheduling row stays present and the next retry verifies the same Core
 * receipt and performs the same DELETE. No new lease can be issued here.
 */
@Service
public class TeacherAcademicLeaseCompletionService {
    public record Key(String actor,String requestKey) {}
    private record Pending(String leaseId,String eventId,String scheduleId,
        long revision,String payloadHash,String payload) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;

    public TeacherAcademicLeaseCompletionService(
            JdbcTemplate db,ObjectMapper json,SchedulingCoreClient core) {
        this.db=db;this.json=json;this.core=core;
    }

    public List<Key> pending(int limit) {
        if(limit<1 || limit>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Teacher lease completion scan must contain 1..50 keys");
        return db.query(
            "SELECT l.actor_username,l.request_key "+
            "FROM scheduling_academic_command_leases l "+
            "JOIN scheduling_assignment_command_inbox c "+
            "ON c.actor_username=l.actor_username AND c.operation=l.operation "+
            "AND c.request_key=l.request_key "+
            "WHERE l.operation='ASSIGN_TEACHER' AND c.state='COMMITTED' "+
            "ORDER BY l.refreshed_at,l.actor_username,l.request_key LIMIT ?",
            (rs,i)->new Key(rs.getString(1),rs.getString(2)),limit);
    }

    @Transactional
    public boolean complete(String actor,String key) {
        if(actor==null || !actor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact teacher command key required");
        List<Pending> events=db.query(
            "SELECT l.core_lease_id,e.event_id,e.schedule_id,e.revision,"+
            "e.payload_hash,e.payload::text "+
            "FROM scheduling_academic_command_leases l "+
            "JOIN scheduling_assignment_command_inbox c "+
            "ON c.actor_username=l.actor_username AND c.operation=l.operation "+
            "AND c.request_key=l.request_key "+
            "JOIN scheduling_assignment_domain_outbox e "+
            "ON e.actor_username=c.actor_username AND e.operation=c.operation "+
            "AND e.request_key=c.request_key "+
            "WHERE c.actor_username=? AND c.operation='ASSIGN_TEACHER' "+
            "AND c.request_key=? AND c.state='COMMITTED' "+
            "AND e.source_type='TEACHING' AND e.event_kind='UPSERT' "+
            "AND e.revision=1 AND e.delivered_at IS NOT NULL "+
            "FOR UPDATE OF l,c,e",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6)),
            actor,key);
        if(events.isEmpty())return false; // Already finalized, or not COMMITTED.
        if(events.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher command has multiple finalizing reference events");
        var item=events.get(0);
        Long total=db.queryForObject(
            "SELECT COUNT(*) FROM scheduling_assignment_domain_outbox "+
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            Long.class,actor,key);
        if(!Long.valueOf(1).equals(total))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher command has incomplete or additional source events");
        var proof=core.committedAssignmentReceipt("TEACHING",item.scheduleId(),
            item.eventId());
        if(proof==null || !Objects.equals(item.eventId(),proof.eventId()) ||
            item.revision()!=proof.revision() ||
            !Objects.equals(item.payloadHash(),proof.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core immutable teacher reference receipt differs");
        try {
            JsonNode event=json.readTree(item.payload());
            if(event==null || !item.scheduleId().equals(event.path("id").asText()) ||
                !Objects.equals(proof.teacherId(),event.path("teacherId").asText()) ||
                !Objects.equals(proof.classId(),event.path("classId").asText()) ||
                !Objects.equals(proof.date(),LocalDate.parse(event.path("date").asText())) ||
                !Objects.equals(proof.startTime(),
                    LocalTime.parse(event.path("startTime").asText())) ||
                !Objects.equals(proof.endTime(),
                    LocalTime.parse(event.path("endTime").asText())) ||
                !"ASSIGNED".equals(proof.status()) ||
                !"ASSIGNED".equals(event.path("status").asText()) ||
                !Objects.equals(proof.assignedBy(),event.path("assignedBy").asText()))
                throw new IllegalStateException("Teacher Core ref facts differ from source");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher reference payload and Core receipt disagree",ex);
        }
        // The external DELETE happens before local deletion, so a timeout
        // cannot erase our durable retry cursor. The inverse order is unsafe.
        core.releaseAcademicSession(item.leaseId());
        int changed=db.update(
            "DELETE FROM scheduling_academic_command_leases "+
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' "+
            "AND request_key=? AND core_lease_id=?",
            actor,key,item.leaseId());
        if(changed!=1)
            throw new IllegalStateException(
                "Academic lease removed remotely but local completion not persisted");
        return true;
    }
}

package vn.iig.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Privately gated migration writer for EDIT_STAFF of a previously confirmed
 * standalone CS/Sale shift. Employee and role are intentionally immutable;
 * assigning a different employee requires a separate, coordinated saga.
 * No public assignment route uses this executor yet.
 */
@Service
public class StandaloneStaffEditExecutor {
    private record Command(String state,String payload) {}
    private record Shift(long version,boolean deleted,String employeeId,
            String staffRole,String classId,String status,String payload) {}
    private record Lease(String employeeName) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;
    private final AssignmentProjectionRepository projection;
    private final AssignmentWriteConflictGuard conflicts;
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;

    public StandaloneStaffEditExecutor(JdbcTemplate db,ObjectMapper json,
            SchedulingCoreClient core,AssignmentProjectionRepository projection,
            AssignmentWriteConflictGuard conflicts,
            AssignmentDomainOutboxRepository outbox,
            AssignmentCommandLifecycleService lifecycle) {
        this.db=db;this.json=json;this.core=core;this.projection=projection;
        this.conflicts=conflicts;this.outbox=outbox;this.lifecycle=lifecycle;
    }

    @Transactional
    public StandaloneStaffAssignmentExecutor.Result edit(
            String username,String managerId,String role,String requestKey) {
        if(!"CM".equals(role) || username==null || username.isBlank() ||
                managerId==null || managerId.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an active CM may edit standalone staff shifts");
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A valid EDIT_STAFF command key is required");

        core.requireCoreWriterQuiesced();
        projection.requireReady();

        List<Command> commands=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='EDIT_STAFF' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2)),
            username,requestKey);
        if(commands.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Previously staged EDIT_STAFF command missing");
        Command command=commands.get(0);
        if(Set.of("PENDING_REFERENCE","COMMITTED").contains(command.state())) {
            List<StandaloneStaffAssignmentExecutor.Result> existing=db.query(
                "SELECT schedule_id,revision FROM scheduling_assignment_domain_outbox " +
                "WHERE actor_username=? AND operation='EDIT_STAFF' " +
                "AND request_key=? ORDER BY created_at,event_id LIMIT 2",
                (rs,i)->new StandaloneStaffAssignmentExecutor.Result(
                    rs.getString(1),command.state(),rs.getLong(2)),
                username,requestKey);
            if(existing.size()!=1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Edit command and domain event disagree");
            return existing.get(0);
        }
        if(!"STAGED".equals(command.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Edit command is rejected or awaiting reconciliation");

        JsonNode envelope;
        try { envelope=json.readTree(command.payload()); }
        catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Corrupted staged edit command",ex);
        }
        if(envelope==null || !envelope.isObject() ||
                !"EDIT_STAFF".equals(text(envelope,"operation")) ||
                !envelope.hasNonNull("targetId") ||
                !envelope.has("payload") || !envelope.get("payload").isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Standalone staff edit requires targetId and JSON payload");
        String scheduleId=text(envelope,"targetId");
        if(scheduleId==null || scheduleId.isBlank() || scheduleId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid edit shift identifier");

        JsonNode input=envelope.get("payload");
        String employeeOverride=text(input,"employeeId");
        String roleOverride=text(input,"staffRole");
        String classOverride=text(input,"classId");
        if((employeeOverride!=null && !employeeOverride.isBlank()) ||
                (roleOverride!=null && !roleOverride.isBlank()) ||
                (classOverride!=null && !classOverride.isBlank()) ||
                input.hasNonNull("id") || input.hasNonNull("assignedBy") ||
                input.hasNonNull("status"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Edit cannot reassign employee, role, class or change status");

        String reservationId=text(input,"reservationId");
        String workType=text(input,"workType");
        String workplace=text(input,"workplaceId");
        if(reservationId==null || reservationId.isBlank() ||
                reservationId.length()>50 || workType==null ||
                (workplace!=null && workplace.length()>50))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Edited shift requires approved reservation and valid work type");

        LocalDate date;
        LocalTime start,end;
        try {
            date=LocalDate.parse(text(input,"date"));
            start=LocalTime.parse(text(input,"startTime"));
            end=LocalTime.parse(text(input,"endTime"));
            if(!end.isAfter(start)) throw new IllegalArgumentException("Invalid time range");
        } catch(RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid date/start/end required for edited shift",ex);
        }

        List<Shift> shifts=db.query(
            "SELECT s.version,s.deleted,s.employee_id,s.staff_role,s.class_id," +
            "s.status,r.payload::text FROM scheduling_assignment_write_shadow s " +
            "JOIN scheduling_assignment_read_model r ON r.source_type=s.source_type " +
            "AND r.schedule_id=s.schedule_id " +
            "WHERE s.source_type='STAFF' AND s.schedule_id=? FOR UPDATE OF s,r",
            (rs,i)->new Shift(rs.getLong(1),rs.getBoolean(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),
            scheduleId);
        if(shifts.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Standalone shift does not exist");
        Shift old=shifts.get(0);
        if(old.deleted() || old.version()<1 || old.employeeId()==null ||
                old.employeeId().isBlank() || old.staffRole()==null ||
                !Set.of("CS","SALE").contains(old.staffRole()) ||
                (old.classId()!=null && !old.classId().isBlank()) ||
                !"ASSIGNED".equals(old.status()) ||
                !(("CS".equals(old.staffRole()) &&
                    Set.of("OPERATIONS_SUPPORT","FRONT_DESK").contains(workType)) ||
                  ("SALE".equals(old.staffRole()) &&
                    Set.of("SALES_SHIFT","LEAD_FOLLOWUP","CONSULTATION").contains(workType))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only active standalone CS/Sale shifts may be edited");

        // Require a known Scheduling-created, Core-confirmed shift and all
        // revisions fully acknowledged. Never edit a mirrored legacy Core row.
        Long ownership=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox o " +
            "JOIN scheduling_assignment_command_inbox i ON " +
            "i.actor_username=o.actor_username AND i.operation=o.operation " +
            "AND i.request_key=o.request_key " +
            "WHERE o.source_type='STAFF' AND o.schedule_id=? " +
            "AND o.revision=1 AND o.operation='ASSIGN_STAFF' " +
            "AND o.delivered_at IS NOT NULL AND i.state='COMMITTED'",
            Long.class,scheduleId);
        Long confirmed=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE source_type='STAFF' AND schedule_id=? AND revision=? " +
            "AND delivered_at IS NOT NULL",
            Long.class,scheduleId,old.version());
        if(ownership==null || ownership!=1 || confirmed==null || confirmed!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Editing requires an entirely Core-confirmed Scheduling-owned shift");

        // Approved availability is authoritative in Scheduling, and must
        // belong to the SAME worker and role as the committed shift.
        List<Lease> leases=db.query(
            "SELECT a.full_name FROM scheduling_availability_reservations lease " +
            "JOIN scheduling_availabilities a ON a.availability_id=lease.availability_id " +
            "JOIN scheduling_assignment_intent_reservations intent " +
            "ON intent.reservation_id=lease.reservation_id " +
            "WHERE lease.reservation_id=? AND lease.employee_id=? " +
            "AND lease.expires_at>clock_timestamp() AND a.deleted=FALSE " +
            "AND a.status='APPROVED' AND a.employee_id=? AND a.role_id=? " +
            "AND intent.employee_id=? AND intent.work_date=? " +
            "AND intent.start_time<=? AND intent.end_time>=? " +
            "FOR UPDATE OF lease,intent",
            (rs,i)->new Lease(rs.getString(1)),reservationId,old.employeeId(),
            old.employeeId(),old.staffRole(),old.employeeId(),date,start,end);
        if(leases.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Approved edit reservation missing or expired");
        conflicts.requireUnoccupied(new AssignmentWriteConflictGuard.Candidate(
            "STAFF",old.employeeId(),null,scheduleId,date,start,end,reservationId));

        JsonNode prior;
        try { prior=json.readTree(old.payload()); }
        catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Corrupt committed Scheduling shift read model",ex);
        }
        if(prior==null || !scheduleId.equals(text(prior,"id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Edited shift identity does not match its read projection");

        long revision=old.version()+1;
        var snapshot=new AssignmentProjectionRepository.AssignmentSnapshot(
            "STAFF",scheduleId,old.employeeId(),leases.get(0).employeeName(),
            old.staffRole(),null,null,null,workplace,date,start,end,workType,
            "ASSIGNED",managerId,revision,false);
        ObjectNode event=json.createObjectNode();
        event.put("id",scheduleId);
        event.put("employeeId",old.employeeId());
        event.put("employeeName",leases.get(0).employeeName());
        event.put("staffRole",old.staffRole());
        event.put("workType",workType);
        event.put("status","ASSIGNED");
        event.put("date",date.toString());
        event.put("startTime",start.toString());
        event.put("endTime",end.toString());
        event.putNull("classId");
        if(workplace==null) event.putNull("workplaceId");
        else event.put("workplaceId",workplace);
        event.put("assignedBy",managerId);

        projection.project(snapshot);
        outbox.append(username,"EDIT_STAFF",requestKey,
            "STAFF",scheduleId,revision,"UPSERT",event);
        lifecycle.markLocallyApplied(username,"EDIT_STAFF",requestKey);
        int released=db.update(
            "DELETE FROM scheduling_availability_reservations " +
            "WHERE reservation_id=? AND employee_id=? " +
            "AND expires_at>clock_timestamp()",reservationId,old.employeeId());
        if(released!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Edit reservation expired before transaction commit");
        return new StandaloneStaffAssignmentExecutor.Result(
            scheduleId,"PENDING_REFERENCE",revision);
    }

    private String text(JsonNode input,String key) {
        return input.hasNonNull(key)?input.get(key).asText():null;
    }
}

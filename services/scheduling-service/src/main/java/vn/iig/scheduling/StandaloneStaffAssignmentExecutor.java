package vn.iig.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
 * First executable, narrowly scoped Scheduling-owned command:
 * CREATE a standalone CS/Sale shift, never teacher/class staffing/attendance.
 *
 * Core must be QUIESCED and the controller must be explicitly enabled. The
 * authenticated caller must be an active CM. A previously approved, unexpired
 * worker/time reservation plus the committed shadow conflict guard is required.
 *
 * One Scheduling transaction writes the FE read model, normalized authoritative
 * candidate, immutable Core reference event and PENDING_REFERENCE inbox state.
 * The request is NOT successful in Core until the private relay confirms it.
 * Network failures never trigger a blind rollback of this local transaction.
 */
@Service
public class StandaloneStaffAssignmentExecutor {
    public record Result(String scheduleId,String state,long revision) {}
    private record Stored(String state,String payload) {}
    private record Availability(String name,String role) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;
    private final AssignmentProjectionRepository projection;
    private final AssignmentWriteConflictGuard conflicts;
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;

    public StandaloneStaffAssignmentExecutor(JdbcTemplate db,ObjectMapper json,
            SchedulingCoreClient core,AssignmentProjectionRepository projection,
            AssignmentWriteConflictGuard conflicts,
            AssignmentDomainOutboxRepository outbox,
            AssignmentCommandLifecycleService lifecycle) {
        this.db=db;
        this.json=json;
        this.core=core;
        this.projection=projection;
        this.conflicts=conflicts;
        this.outbox=outbox;
        this.lifecycle=lifecycle;
    }

    @Transactional
    public Result execute(String actorUsername,String actorEmployeeId,
                          String actorRole,String requestKey) {
        if(!"CM".equals(actorRole) || actorUsername==null ||
                actorUsername.isBlank() || actorEmployeeId==null ||
                actorEmployeeId.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only a currently active CM may create standalone staff shifts");
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A valid previously staged command key is required");

        // This is a migration-only precondition, not a distributed lock.
        // The Core reference consumer enforces QUIESCED again under Core SQL.
        core.requireCoreWriterQuiesced();
        projection.requireReady();
        List<Stored> commands=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_STAFF' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2)),
            actorUsername,requestKey);
        if(commands.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Staged ASSIGN_STAFF command not found for this actor");

        Stored command=commands.get(0);
        if(Set.of("COMMITTED","PENDING_REFERENCE").contains(command.state())) {
            List<Result> existing=db.query(
                "SELECT schedule_id,revision FROM scheduling_assignment_domain_outbox " +
                "WHERE actor_username=? AND operation='ASSIGN_STAFF' " +
                "AND request_key=? ORDER BY created_at,event_id LIMIT 2",
                (rs,i)->new Result(rs.getString(1),command.state(),rs.getLong(2)),
                actorUsername,requestKey);
            if(existing.size()!=1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Command state and immutable event disagree");
            return existing.get(0);
        }
        if(!"STAGED".equals(command.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Assignment command is rejected or awaiting recovery");

        JsonNode envelope;
        try {
            envelope=json.readTree(command.payload());
        } catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Corrupted staged assignment request",ex);
        }
        if(envelope==null || !"ASSIGN_STAFF".equals(string(envelope,"operation")) ||
                envelope.hasNonNull("targetId") || !envelope.has("payload") ||
                !envelope.get("payload").isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only new standalone staff shifts can be executed");
        JsonNode payload=envelope.get("payload");
        String employeeId=string(payload,"employeeId");
        String staffRole=string(payload,"staffRole");
        String workType=string(payload,"workType");
        String reservationId=string(payload,"reservationId");
        String workplaceId=string(payload,"workplaceId");
        String classId=string(payload,"classId");
        String status=string(payload,"status");
        if(employeeId==null || employeeId.isBlank() || employeeId.length()>50 ||
                staffRole==null || !Set.of("CS","SALE").contains(staffRole) ||
                reservationId==null || reservationId.isBlank() || reservationId.length()>50 ||
                (classId!=null && !classId.isBlank()) ||
                payload.hasNonNull("id") || payload.hasNonNull("assignedBy") ||
                (status!=null && !"ASSIGNED".equals(status)) ||
                (workplaceId!=null && workplaceId.length()>50) ||
                workType==null || !(
                    ("CS".equals(staffRole) &&
                        Set.of("OPERATIONS_SUPPORT","FRONT_DESK").contains(workType)) ||
                    ("SALE".equals(staffRole) &&
                        Set.of("SALES_SHIFT","LEAD_FOLLOWUP","CONSULTATION").contains(workType))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported standalone CS/Sale shift command");

        LocalDate date;
        LocalTime start,end;
        try {
            date=LocalDate.parse(string(payload,"date"));
            start=LocalTime.parse(string(payload,"startTime"));
            end=LocalTime.parse(string(payload,"endTime"));
            if(!end.isAfter(start)) throw new IllegalArgumentException("end <= start");
        } catch(RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid date/startTime/endTime required",ex);
        }

        // Approved worker availability is authoritative in Scheduling. Do
        // not rely on caller-supplied fullName or role. The intent must cover
        // the entire shift and belong to the requested employee.
        List<Availability> eligibility=db.query(
            "SELECT a.full_name,a.role_id FROM scheduling_availability_reservations lease " +
            "JOIN scheduling_availabilities a ON a.availability_id=lease.availability_id " +
            "JOIN scheduling_assignment_intent_reservations intent " +
            "ON intent.reservation_id=lease.reservation_id " +
            "WHERE lease.reservation_id=? AND lease.employee_id=? " +
            "AND lease.expires_at>clock_timestamp() AND a.deleted=FALSE " +
            "AND a.status='APPROVED' AND a.employee_id=? AND a.role_id=? " +
            "AND intent.employee_id=? AND intent.work_date=? " +
            "AND intent.start_time<=? AND intent.end_time>=? " +
            "FOR UPDATE OF lease,intent",
            (rs,i)->new Availability(rs.getString(1),rs.getString(2)),
            reservationId,employeeId,employeeId,staffRole,employeeId,date,start,end);
        if(eligibility.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Approved worker availability lease missing or expired");

        conflicts.requireUnoccupied(new AssignmentWriteConflictGuard.Candidate(
            "STAFF",employeeId,null,null,date,start,end,reservationId));

        String scheduleId=UUID.randomUUID().toString();
        var snapshot=new AssignmentProjectionRepository.AssignmentSnapshot(
            "STAFF",scheduleId,employeeId,eligibility.get(0).name(),
            staffRole,null,null,null,workplaceId,date,start,end,workType,
            "ASSIGNED",actorEmployeeId,1,false);

        // An independent event envelope uses exactly the FE/reference fields
        // understood by Core; the server controls all identity and status.
        ObjectNode event=json.createObjectNode();
        event.put("id",scheduleId);
        event.put("employeeId",employeeId);
        event.put("employeeName",eligibility.get(0).name());
        event.put("staffRole",staffRole);
        event.put("workType",workType);
        event.put("status","ASSIGNED");
        event.put("date",date.toString());
        event.put("startTime",start.toString());
        event.put("endTime",end.toString());
        if(workplaceId==null) event.putNull("workplaceId");
        else event.put("workplaceId",workplaceId);
        event.putNull("classId");
        event.put("assignedBy",actorEmployeeId);

        projection.project(snapshot);
        outbox.append(actorUsername,"ASSIGN_STAFF",requestKey,
            "STAFF",scheduleId,1,"UPSERT",event);
        lifecycle.markLocallyApplied(actorUsername,"ASSIGN_STAFF",requestKey);
        // Delete lease and the intent (FK cascade) only after the local write
        // and event have been safely staged in the SAME transaction.
        int released=db.update("DELETE FROM scheduling_availability_reservations " +
            "WHERE reservation_id=? AND employee_id=? " +
            "AND expires_at>clock_timestamp()",reservationId,employeeId);
        if(released!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Assignment lease expired before the local command committed");
        return new Result(scheduleId,"PENDING_REFERENCE",1);
    }

    private String string(JsonNode source,String field) {
        return source.hasNonNull(field)?source.get(field).asText():null;
    }
}

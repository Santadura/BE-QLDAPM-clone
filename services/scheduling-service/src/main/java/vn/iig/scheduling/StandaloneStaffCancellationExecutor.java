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
 * Restricted independent cancellation: standalone CS/Sale shifts ONLY.
 * No teaching schedules or class-linked support shift may be touched.
 * Cancellation retains the row and increments its source revision, allowing
 * an at-least-once Core reference UPSERT to set status=CANCELLED idempotently.
 */
@Service
public class StandaloneStaffCancellationExecutor {
    private record Command(String state,String payload) {}
    private record Current(long version,boolean deleted,String employeeId,String staffRole,
            String classId,String workplaceId,LocalDate date,LocalTime start,
            LocalTime end,String workType,String status,String payloadJson) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;
    private final AssignmentProjectionRepository projection;
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;

    public StandaloneStaffCancellationExecutor(JdbcTemplate db,ObjectMapper json,
            SchedulingCoreClient core,AssignmentProjectionRepository projection,
            AssignmentDomainOutboxRepository outbox,
            AssignmentCommandLifecycleService lifecycle) {
        this.db=db;this.json=json;this.core=core;
        this.projection=projection;this.outbox=outbox;this.lifecycle=lifecycle;
    }

    @Transactional
    public StandaloneStaffAssignmentExecutor.Result cancel(
            String actorUsername,String actorEmployeeId,String actorRole,String requestKey) {
        if(!"CM".equals(actorRole) || actorUsername==null || actorUsername.isBlank() ||
                actorEmployeeId==null || actorEmployeeId.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an active CM may cancel standalone staff shifts");
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid staged cancellation key required");
        core.requireCoreWriterQuiesced();
        projection.requireReady();
        List<Command> inbox=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='CANCEL_STAFF' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2)),
            actorUsername,requestKey);
        if(inbox.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Staged standalone staff cancellation missing");

        Command command=inbox.get(0);
        if(Set.of("COMMITTED","PENDING_REFERENCE").contains(command.state())) {
            List<StandaloneStaffAssignmentExecutor.Result> receipts=db.query(
                "SELECT schedule_id,revision FROM scheduling_assignment_domain_outbox " +
                "WHERE actor_username=? AND operation='CANCEL_STAFF' " +
                "AND request_key=? ORDER BY created_at,event_id LIMIT 2",
                (rs,i)->new StandaloneStaffAssignmentExecutor.Result(
                    rs.getString(1),command.state(),rs.getLong(2)),
                actorUsername,requestKey);
            if(receipts.size()!=1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cancellation receipt and event disagree");
            return receipts.get(0);
        }
        if(!"STAGED".equals(command.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cancellation request was rejected or requires recovery");

        JsonNode request;
        try { request=json.readTree(command.payload()); }
        catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Corrupted staged cancellation request",ex);
        }
        if(request==null || !request.isObject() ||
                !"CANCEL_STAFF".equals(text(request,"operation")) ||
                request.hasNonNull("payload"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only a previously staged cancellation is supported");
        String target=text(request,"targetId");
        if(target==null || target.isBlank() || target.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A valid standalone shift ID is required");

        List<Current> rows=db.query(
            "SELECT s.version,s.deleted,s.employee_id,s.staff_role,s.class_id," +
            "s.workplace_id,s.work_date,s.start_time,s.end_time,s.work_type," +
            "s.status,r.payload::text FROM scheduling_assignment_write_shadow s " +
            "JOIN scheduling_assignment_read_model r " +
            "ON r.source_type=s.source_type AND r.schedule_id=s.schedule_id " +
            "WHERE s.source_type='STAFF' AND s.schedule_id=? FOR UPDATE OF s,r",
            (rs,i)->new Current(rs.getLong(1),rs.getBoolean(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getObject(7,LocalDate.class),rs.getObject(8,LocalTime.class),
                rs.getObject(9,LocalTime.class),rs.getString(10),
                rs.getString(11),rs.getString(12)),target);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Standalone staff shift not found in Scheduling");
        Current current=rows.get(0);
        if(current.deleted() || !"ASSIGNED".equals(current.status()) ||
                (current.classId()!=null && !current.classId().isBlank()) ||
                current.staffRole()==null ||
                !Set.of("CS","SALE").contains(current.staffRole()) ||
                current.employeeId()==null || current.employeeId().isBlank() ||
                current.version()<1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only an active standalone CS/Sale shift can be cancelled");

        // A copied Core shift can look identical to a standalone
        // Scheduling-owned shift in the normalized/read model. Never cancel
        // such legacy rows: require the exact revision-1 create event to have
        // been delivered AND its original command fully committed in Core.
        Long owned=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox o " +
            "JOIN scheduling_assignment_command_inbox inbox " +
            "ON inbox.actor_username=o.actor_username " +
            "AND inbox.operation=o.operation AND inbox.request_key=o.request_key " +
            "WHERE o.source_type='STAFF' AND o.schedule_id=? " +
            "AND o.revision=1 AND o.operation='ASSIGN_STAFF' " +
            "AND o.event_kind='UPSERT' AND o.delivered_at IS NOT NULL " +
            "AND inbox.state='COMMITTED'",
            Long.class,target);
        if(owned==null || owned!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only Core-confirmed Scheduling-owned standalone shifts can be cancelled");

        // Consistent with availability reservation and new-shift creation:
        // serialize a cancellation with concurrent assignment commands.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",current.employeeId());

        JsonNode original;
        try { original=json.readTree(current.payloadJson()); }
        catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Corrupted scheduling read model",ex);
        }
        if(original==null || !target.equals(text(original,"id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stored projection does not match the cancelled shift");

        long nextRevision=current.version()+1;
        var snapshot=new AssignmentProjectionRepository.AssignmentSnapshot(
            "STAFF",target,current.employeeId(),text(original,"employeeName"),
            current.staffRole(),null,null,null,current.workplaceId(),
            current.date(),current.start(),current.end(),current.workType(),
            "CANCELLED",actorEmployeeId,nextRevision,false);

        ObjectNode event=json.createObjectNode();
        event.put("id",target);
        event.put("employeeId",current.employeeId());
        if(original.hasNonNull("employeeName"))
            event.put("employeeName",original.get("employeeName").asText());
        event.put("staffRole",current.staffRole());
        event.put("workType",current.workType());
        event.put("status","CANCELLED");
        event.put("date",current.date().toString());
        event.put("startTime",current.start().toString());
        event.put("endTime",current.end().toString());
        event.putNull("classId");
        if(current.workplaceId()==null) event.putNull("workplaceId");
        else event.put("workplaceId",current.workplaceId());
        event.put("assignedBy",actorEmployeeId);

        // Durable Core claim is required before advancing source revision.
        // An uncertain local rollback must not permit Core split-brain writes.
        core.acquireAssignmentWriterClaim(actorUsername,actorEmployeeId,
            "CANCEL_STAFF",requestKey);
        projection.project(snapshot);
        outbox.append(actorUsername,"CANCEL_STAFF",requestKey,
            "STAFF",target,nextRevision,"UPSERT",event);
        lifecycle.markLocallyApplied(actorUsername,"CANCEL_STAFF",requestKey);
        return new StandaloneStaffAssignmentExecutor.Result(
            target,"PENDING_REFERENCE",nextRevision);
    }

    private String text(JsonNode input,String field) {
        return input.hasNonNull(field)?input.get(field).asText():null;
    }
}

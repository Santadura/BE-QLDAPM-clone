package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PRIVATE ONLY: first independent Scheduling-owned TEACHING CREATE transaction.
 * Feature-gated at the controller and not exposed via Gateway.
 *
 * Core admission, generation-fenced Academic lease, approved teacher
 * availability, Core/source parity and all conflicting worker/class
 * reservations must be verified before the local immutable source event.
 *
 * A Core reference may still be pending when this returns; PENDING_REFERENCE
 * is never a statement that the class has a finalized teacher assignment.
 */
@Service
public class TeacherAssignmentExecutor {
    private static final String OPERATION="ASSIGN_TEACHER";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;
    private final TeacherAssignmentPreflightService preflight;
    private final AcademicCommandLeaseService academic;
    private final AssignmentProjectionRepository projection;
    private final AssignmentWriteConflictGuard conflicts;
    private final AssignmentDomainOutboxRepository outbox;
    private final AssignmentCommandLifecycleService lifecycle;

    public record Result(String scheduleId,String state,long revision) {}
    private record Command(String state,String payload) {}
    private record Worker(String name) {}

    public TeacherAssignmentExecutor(JdbcTemplate db,ObjectMapper json,
            SchedulingCoreClient core,TeacherAssignmentPreflightService preflight,
            AcademicCommandLeaseService academic,AssignmentProjectionRepository projection,
            AssignmentWriteConflictGuard conflicts,AssignmentDomainOutboxRepository outbox,
            AssignmentCommandLifecycleService lifecycle) {
        this.db=db;this.json=json;this.core=core;this.preflight=preflight;
        this.academic=academic;this.projection=projection;this.conflicts=conflicts;
        this.outbox=outbox;this.lifecycle=lifecycle;
    }

    @Transactional
    public Result execute(String actor,String employeeId,String role,String key) {
        if(!"TC".equals(role) || actor==null || actor.isBlank() ||
                employeeId==null || employeeId.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only the currently authenticated TC may assign a teacher");
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A previously staged teacher command key is required");
        core.requireCoreWriterQuiesced();
        projection.requireReady();
        List<Command> rows=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2)),actor,key);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Staged teacher assignment not found");
        Command command=rows.get(0);
        if(Set.of("COMMITTED","PENDING_REFERENCE").contains(command.state())) {
            List<Result> existing=db.query(
                "SELECT schedule_id,revision FROM scheduling_assignment_domain_outbox " +
                "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
                "AND request_key=? ORDER BY created_at,event_id LIMIT 2",
                (rs,i)->new Result(rs.getString(1),command.state(),rs.getLong(2)),
                actor,key);
            if(existing.size()!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Previously applied teacher command has no unique immutable event");
            return existing.get(0);
        }
        if(!"STAGED".equals(command.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher command is rejected or awaiting reconciliation");

        JsonNode payload;
        try {
            JsonNode envelope=json.readTree(command.payload());
            if(envelope==null || !OPERATION.equals(envelope.path("operation").asText()) ||
                    !envelope.path("targetId").isNull() ||
                    !envelope.path("payload").isObject())
                throw new IllegalArgumentException("Invalid staged teacher envelope");
            payload=envelope.get("payload");
            if(payload.hasNonNull("id") || payload.hasNonNull("assignedBy") ||
                    payload.hasNonNull("status") || payload.hasNonNull("academicLeaseId"))
                throw new IllegalArgumentException("Worker cannot set teacher source identity");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid or spoofed teacher command payload",ex);
        }

        var receipt=preflight.prepare(actor,key);
        if(!"PREFLIGHT_ONLY".equals(receipt.state()) ||
                receipt.classCode()==null || receipt.classCode().isBlank() ||
                receipt.className()==null || receipt.className().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Versioned Academic class display/reference metadata unavailable");

        String teacher=receipt.teacherId();
        if(!teacher.equals(payload.path("teacherId").asText()) ||
                !receipt.classId().equals(payload.path("classId").asText()) ||
                !receipt.sessionId().equals(payload.path("sessionId").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Staged teacher command and source preflight disagree");
        long classVersion=payload.path("classVersion").asLong(0);
        long sessionVersion=payload.path("sessionVersion").asLong(0);
        if(classVersion<1 || sessionVersion<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic revision evidence is required");

        // This durable Core claim is taken BEFORE any independent teacher
        // source write. It never silently expires and fences handback if the
        // Scheduling transaction subsequently crashes or rolls back.
        core.acquireAssignmentWriterClaim(actor,employeeId,OPERATION,key);
        var current=academic.renew(actor,key);
        if(!receipt.academicLeaseId().equals(current.leaseId()) ||
                classVersion!=current.classVersion() ||
                sessionVersion!=current.sessionVersion() ||
                !receipt.classId().equals(current.classId()) ||
                !receipt.sessionId().equals(current.sessionId()) ||
                current.expiresAt()==null ||
                !current.expiresAt().isAfter(Instant.now().plusSeconds(45)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic writer lease changed or is too close to expiry");

        // The approved worker lease remains held until the local commit.
        // Holding a live reservation and the same advisory worker/class locks
        // makes overlapping teacher assignments serializable in Scheduling.
        List<Worker> workers=db.query(
            "SELECT a.full_name FROM scheduling_availability_reservations r " +
            "JOIN scheduling_availabilities a ON a.availability_id=r.availability_id " +
            "JOIN scheduling_assignment_intent_reservations i " +
            "ON i.reservation_id=r.reservation_id " +
            "WHERE r.reservation_id=? AND r.employee_id=? " +
            "AND r.expires_at>clock_timestamp()+interval '45 seconds' " +
            "AND a.status='APPROVED' AND a.deleted=FALSE " +
            "AND a.employee_id=? AND a.role_id='TEACHER' " +
            "AND i.employee_id=? AND i.work_date=? " +
            "AND i.start_time<=? AND i.end_time>=? FOR UPDATE OF r,i",
            (rs,i)->new Worker(rs.getString(1)),receipt.workerReservationId(),
            teacher,teacher,teacher,receipt.date(),receipt.startTime(),receipt.endTime());
        if(workers.size()!=1 || workers.get(0).name()==null ||
                workers.get(0).name().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Approved teacher availability expired or lacks a worker identity");
        conflicts.requireUnoccupied(new AssignmentWriteConflictGuard.Candidate(
            "TEACHING",teacher,receipt.classId(),null,receipt.date(),
            receipt.startTime(),receipt.endTime(),receipt.workerReservationId()));

        String id=UUID.randomUUID().toString();
        var candidate=new AssignmentProjectionRepository.AssignmentSnapshot(
            "TEACHING",id,teacher,workers.get(0).name(),
            "TEACHER",receipt.classId(),receipt.classCode(),receipt.className(),
            null,receipt.date(),receipt.startTime(),receipt.endTime(),null,
            "ASSIGNED",employeeId,1,false);

        ObjectNode event=json.createObjectNode();
        event.put("id",id);
        event.put("teacherId",teacher);
        event.put("teacherName",workers.get(0).name());
        event.put("classId",receipt.classId());
        event.put("classCode",receipt.classCode());
        event.put("className",receipt.className());
        event.put("sessionId",receipt.sessionId());
        event.put("classVersion",classVersion);
        event.put("sessionVersion",sessionVersion);
        event.put("academicLeaseId",current.leaseId());
        event.put("status","ASSIGNED");
        event.put("date",receipt.date().toString());
        event.put("startTime",receipt.startTime().toString());
        event.put("endTime",receipt.endTime().toString());
        event.put("assignedBy",employeeId);

        projection.project(candidate);
        outbox.append(actor,OPERATION,key,"TEACHING",id,1,"UPSERT",event);
        lifecycle.markLocallyApplied(actor,OPERATION,key);

        // Release only after the independent read/authoritative shadow and
        // immutable outbox are in one committed Scheduling transaction.
        // FK cascade removes temporary preflight+intent receipts.
        int released=db.update(
            "DELETE FROM scheduling_availability_reservations " +
            "WHERE reservation_id=? AND employee_id=? " +
            "AND expires_at>clock_timestamp()",
            receipt.workerReservationId(),teacher);
        if(released!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher worker reservation expired before local source commit");
        return new Result(id,"PENDING_REFERENCE",1);
    }
}

package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.scheduling.SchedulingModels.ReservationRequest;

/**
 * Cross-service teacher assignment ADMISSION only, not a teaching writer.
 * Core Academic owns the versioned lesson, Scheduling owns approved worker
 * availability/intent and independent conflict checks. No schedule/outbox
 * event is written and no public route can invoke this by default.
 *
 * The Scheduling DB transaction holds the actor's STAGED inbox lock and
 * worker reservation before returning. Core retains its own Academic lease;
 * if the local transaction aborts after a successful Core call, the remote
 * lease expires safely and the same scoped command can retry.
 */
@Service
public class TeacherAssignmentPreflightService {
    private static final String OPERATION="ASSIGN_TEACHER";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SchedulingCoreClient core;
    private final AssignmentCutoverVerificationService cutover;
    private final AcademicCommandLeaseService academic;
    private final AvailabilityRepository availability;
    private final AssignmentWriteConflictGuard conflict;

    public record Receipt(String state,String requestKey,String teacherId,
        String classId,String sessionId,LocalDate date,LocalTime startTime,
        LocalTime endTime,String academicLeaseId,Instant academicExpiresAt,
        String workerReservationId,Instant workerExpiresAt,
        String classCode,String className) {}
    private record Command(String state,String payload) {}
    private record Request(String teacherId,String classId,String sessionId,
        long classVersion,long sessionVersion,LocalDate date,LocalTime start,
        LocalTime end,String campus) {}
    private record Existing(String teacherId,String classId,String sessionId,
        LocalDate date,LocalTime start,LocalTime end,String campus,String reservationId) {}
    private record Worker(String role,Instant expiresAt) {}

    public TeacherAssignmentPreflightService(JdbcTemplate db,ObjectMapper json,
            SchedulingCoreClient core,AssignmentCutoverVerificationService cutover,
            AcademicCommandLeaseService academic,AvailabilityRepository availability,
            AssignmentWriteConflictGuard conflict) {
        this.db=db;this.json=json;this.core=core;this.cutover=cutover;
        this.academic=academic;this.availability=availability;this.conflict=conflict;
    }

    private static String required(JsonNode payload,String field) {
        String value=payload.path(field).asText("");
        if(!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid staged teacher "+field);
        return value;
    }

    private Request staged(String actor,String key) {
        if(actor==null || actor.isBlank() || key==null ||
                !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid actor and teacher command key required");
        List<Command> records=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2)),actor,key);
        if(records.size()!=1 || !"STAGED".equals(records.get(0).state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a staged teacher assignment can request an Academic preflight");
        try {
            JsonNode envelope=json.readTree(records.get(0).payload());
            if(envelope==null || !OPERATION.equals(envelope.path("operation").asText()) ||
                    !envelope.path("targetId").isNull() ||
                    !envelope.path("payload").isObject())
                throw new IllegalArgumentException("Invalid command envelope");
            JsonNode p=envelope.path("payload");
            String employee=required(p,"teacherId");
            String cls=required(p,"classId");
            String session=required(p,"sessionId");
            long cv=p.path("classVersion").asLong(0),sv=p.path("sessionVersion").asLong(0);
            LocalDate date=LocalDate.parse(p.path("date").asText());
            LocalTime start=LocalTime.parse(p.path("startTime").asText());
            LocalTime end=LocalTime.parse(p.path("endTime").asText());
            String campus=p.hasNonNull("campus")?p.get("campus").asText():null;
            if(cv<1 || sv<1 || !end.isAfter(start) ||
                    (campus!=null && campus.length()>100))
                throw new IllegalArgumentException("Invalid session versions or worker slot");
            return new Request(employee,cls,session,cv,sv,date,start,end,campus);
        } catch(ResponseStatusException ex) {
            throw ex;
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Teacher preflight requires a complete staged, versioned lesson",ex);
        }
    }

    private SchedulingCoreClient.AcademicSessionFacts checkAcademicFacts(Request p) {
        var facts=core.academicSession(p.classId(),p.date(),p.start(),p.end());
        if(facts==null || !p.classId().equals(facts.classId()) ||
                !p.sessionId().equals(facts.sessionId()) ||
                p.classVersion()!=facts.classVersion() ||
                p.sessionVersion()!=facts.sessionVersion() ||
                !java.util.Set.of("READY","RUNNING").contains(facts.classStatus()) ||
                !"PLANNED".equals(facts.sessionStatus()) ||
                !p.date().equals(facts.date()) ||
                !p.start().equals(facts.startTime()) ||
                !p.end().equals(facts.endTime()) ||
                facts.classStart()==null || facts.classEnd()==null ||
                p.date().isBefore(facts.classStart()) ||
                p.date().isAfter(facts.classEnd()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic class and exact teacher lesson version changed");
        return facts;
    }

    private List<Existing> existing(String actor,String key) {
        return db.query(
            "SELECT employee_id,class_id,session_id,work_date,start_time,end_time," +
            "campus,reservation_id FROM scheduling_teacher_command_preflights " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=? FOR UPDATE",
            (rs,i)->new Existing(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getObject(4,LocalDate.class),rs.getObject(5,LocalTime.class),
                rs.getObject(6,LocalTime.class),rs.getString(7),rs.getString(8)),actor,key);
    }

    private Worker verifyWorker(String reservationId,String teacherId) {
        List<Worker> rows=db.query(
            "SELECT a.role_id,r.expires_at FROM scheduling_availability_reservations r " +
            "JOIN scheduling_availabilities a ON a.availability_id=r.availability_id " +
            "WHERE r.reservation_id=? AND r.employee_id=? " +
            "AND r.expires_at>clock_timestamp() AND a.deleted=FALSE " +
            "AND a.status='APPROVED' FOR SHARE OF r,a",
            (rs,i)->new Worker(rs.getString(1),rs.getTimestamp(2).toInstant()),
            reservationId,teacherId);
        if(rows.size()!=1 || !"TEACHER".equals(rows.get(0).role()) ||
                !rows.get(0).expiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher must hold a live approved TEACHER availability reservation");
        return rows.get(0);
    }

    @Transactional
    public Receipt prepare(String actor,String key) {
        Request p=staged(actor,key);
        core.requireCoreWriterQuiesced();
        var readiness=cutover.verifyStaged(actor,OPERATION,key);
        if(!readiness.ready())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core/Scheduling assignment source not reconciled: "+readiness.state());
        var classFacts=checkAcademicFacts(p);
        // This renews or safely recovers the exact staged command's Academic
        // lease. No assignment changes are performed by the Core lease port.
        var course=academic.acquire(actor,key);
        if(!p.classId().equals(course.classId()) ||
                !p.sessionId().equals(course.sessionId()) ||
                p.classVersion()!=course.classVersion() ||
                p.sessionVersion()!=course.sessionVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic lease and staged teacher work disagree");

        List<Existing> stored=existing(actor,key);
        if(stored.size()>1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicated teacher preflight reservation");
        String workerReservation;
        if(!stored.isEmpty()) {
            Existing old=stored.get(0);
            if(!p.teacherId().equals(old.teacherId()) ||
                    !p.classId().equals(old.classId()) ||
                    !p.sessionId().equals(old.sessionId()) ||
                    !p.date().equals(old.date()) ||
                    !p.start().equals(old.start()) || !p.end().equals(old.end()) ||
                    !Objects.equals(p.campus(),old.campus()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Staged teacher request changed after worker reservation");
            // Existing live reservation is reused. Expired, unconsumed
            // reservations are deleted and their preflight receipt cascades.
            int removed=db.update("DELETE FROM scheduling_availability_reservations " +
                "WHERE reservation_id=? AND expires_at<=NOW()",old.reservationId());
            workerReservation=removed==0?old.reservationId():null;
        } else workerReservation=null;

        if(workerReservation==null) {
            var lease=availability.reserve(new ReservationRequest(
                p.teacherId(),p.date(),p.start(),p.end(),p.campus()));
            if(!lease.approved() || lease.reservationId()==null)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No approved teacher availability covering this exact session");
            workerReservation=lease.reservationId();
        }
        Worker worker=verifyWorker(workerReservation,p.teacherId());
        conflict.requireUnoccupied(new AssignmentWriteConflictGuard.Candidate(
            "TEACHING",p.teacherId(),p.classId(),null,p.date(),p.start(),p.end(),
            workerReservation));
        if(stored.isEmpty() || !workerReservation.equals(stored.get(0).reservationId())) {
            int added=db.update(
                "INSERT INTO scheduling_teacher_command_preflights (" +
                "actor_username,operation,request_key,employee_id,class_id,session_id," +
                "work_date,start_time,end_time,campus,reservation_id) " +
                "VALUES(?,'ASSIGN_TEACHER',?,?,?,?,?,?,?,?,?)",
                actor,key,p.teacherId(),p.classId(),p.sessionId(),p.date(),
                p.start(),p.end(),p.campus(),workerReservation);
            if(added!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Could not persist exclusive teacher worker reservation");
        }
        // A later executor must recheck both leases and source state before
        // writing an assignment. PREFLIGHT_ONLY is never business success.
        return new Receipt("PREFLIGHT_ONLY",key,p.teacherId(),p.classId(),
            p.sessionId(),p.date(),p.start(),p.end(),course.leaseId(),
            course.expiresAt(),workerReservation,worker.expiresAt(),
            classFacts.classCode(),classFacts.className());
    }
}

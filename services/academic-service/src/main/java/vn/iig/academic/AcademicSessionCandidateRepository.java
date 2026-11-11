package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes three independent Academic PG tables in ONE caller transaction.
 * The candidate is never merged into the live Core-source shadow or emitted
 * as a real domain event. No downstream sender consumes REHEARSAL_ONLY.
 */
@Repository
public class AcademicSessionCandidateRepository {
    public record Plan(String actor,String key,String digest,String classId,
        String sessionId,long sourceEventId,long sourceRevision,String action,
        LocalDate day,LocalTime start,LocalTime end,String status,
        long academicGeneration,long attendanceGeneration,long schedulingGeneration) {}
    public record Receipt(String commandId,String requestKey,String classId,
        String sessionId,long sourceEventId,long sourceRevision,String state) {}
    private record Shadow(String classId,long eventId,long revision,String kind) {}
    private record Existing(String commandId,String digest,String classId,
        String sessionId,long eventId,long revision,String action,
        long academicGeneration,long attendanceGeneration,long schedulingGeneration,
        String state) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public AcademicSessionCandidateRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;this.json=json;
    }

    public Receipt stage(Plan plan) {
        List<Shadow> source=db.query(
            "SELECT class_id,source_event_id,source_revision,event_kind "+
            "FROM academic_class_sessions_shadow WHERE class_session_id=? FOR UPDATE",
            (rs,i)->new Shadow(rs.getString(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4)),plan.sessionId());
        if(source.size()!=1 || !"UPSERT".equals(source.get(0).kind()) ||
            !plan.classId().equals(source.get(0).classId()) ||
            source.get(0).eventId()!=plan.sourceEventId() ||
            source.get(0).revision()!=plan.sourceRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic candidate has stale/tombstoned session evidence");

        String candidateId=UUID.randomUUID().toString();
        int inserted=db.update(
            "INSERT INTO academic_session_candidate_commands "+
            "(operator_username,request_key,command_id,request_hash,class_id,"+
            "class_session_id,source_event_id,source_revision,academic_generation,"+
            "attendance_generation,scheduling_generation,action) "+
            "VALUES(?,?,CAST(? AS UUID),?,?,?,?,?,?,?,?,?) "+
            "ON CONFLICT(operator_username,request_key) DO NOTHING",
            plan.actor(),plan.key(),candidateId,plan.digest(),plan.classId(),
            plan.sessionId(),plan.sourceEventId(),plan.sourceRevision(),
            plan.academicGeneration(),plan.attendanceGeneration(),
            plan.schedulingGeneration(),plan.action());

        List<Existing> prior=db.query(
            "SELECT command_id::text,request_hash,class_id,class_session_id,"+
            "source_event_id,source_revision,action,academic_generation,"+
            "attendance_generation,scheduling_generation,state "+
            "FROM academic_session_candidate_commands "+
            "WHERE operator_username=? AND request_key=? FOR UPDATE",
            (rs,i)->new Existing(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getLong(5),rs.getLong(6),
                rs.getString(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),
                rs.getString(11)),plan.actor(),plan.key());
        if(prior.size()!=1 || !plan.digest().equals(prior.get(0).digest()) ||
            !plan.classId().equals(prior.get(0).classId()) ||
            !plan.sessionId().equals(prior.get(0).sessionId()) ||
            plan.sourceEventId()!=prior.get(0).eventId() ||
            plan.sourceRevision()!=prior.get(0).revision() ||
            !plan.action().equals(prior.get(0).action()) ||
            plan.academicGeneration()!=prior.get(0).academicGeneration() ||
            plan.attendanceGeneration()!=prior.get(0).attendanceGeneration() ||
            plan.schedulingGeneration()!=prior.get(0).schedulingGeneration() ||
            !"STAGED".equals(prior.get(0).state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Candidate idempotency key conflicts with different evidence");
        String committedId=prior.get(0).commandId();
        if(inserted==0) {
            // An idempotent replay MUST have both parts of the atomic write.
            Long slots=db.queryForObject(
                "SELECT COUNT(*) FROM academic_session_candidate_slots "+
                "WHERE command_id=CAST(? AS UUID)",Long.class,committedId);
            Long events=db.queryForObject(
                "SELECT COUNT(*) FROM academic_session_candidate_outbox "+
                "WHERE command_id=CAST(? AS UUID)",Long.class,committedId);
            if(!Long.valueOf(1).equals(slots) || !Long.valueOf(1).equals(events))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Incomplete Academic candidate/outbox evidence");
            return new Receipt(committedId,plan.key(),plan.classId(),
                plan.sessionId(),plan.sourceEventId(),plan.sourceRevision(),"STAGED");
        }

        int slot=db.update(
            "INSERT INTO academic_session_candidate_slots "+
            "(command_id,class_id,class_session_id,session_date,start_time,end_time,"+
            "status,source_event_id,source_revision) "+
            "VALUES(CAST(? AS UUID),?,?,CAST(? AS DATE),CAST(? AS TIME),"+
            "CAST(? AS TIME),?,?,?)",
            committedId,plan.classId(),plan.sessionId(),plan.day().toString(),
            plan.start().toString(),plan.end().toString(),plan.status(),
            plan.sourceEventId(),plan.sourceRevision());

        ObjectNode event=json.createObjectNode();
        event.put("commandId",committedId);
        event.put("operator",plan.actor());
        event.put("requestKey",plan.key());
        event.put("classId",plan.classId());
        event.put("sessionId",plan.sessionId());
        event.put("sourceEventId",plan.sourceEventId());
        event.put("sourceRevision",plan.sourceRevision());
        event.put("action",plan.action());
        event.put("date",plan.day().toString());
        event.put("startTime",plan.start().toString());
        event.put("endTime",plan.end().toString());
        event.put("status",plan.status());
        event.put("academicGeneration",plan.academicGeneration());
        event.put("attendanceGeneration",plan.attendanceGeneration());
        event.put("schedulingGeneration",plan.schedulingGeneration());
        String payload=event.toString();
        int outbox=db.update(
            "INSERT INTO academic_session_candidate_outbox "+
            "(outbox_id,command_id,event_kind,payload,payload_hash) "+
            "VALUES(CAST(? AS UUID),CAST(? AS UUID),'REHEARSAL_ONLY',"+
            "CAST(? AS JSONB),?)",
            UUID.randomUUID().toString(),committedId,payload,
            AcademicSessionSourceRepository.sha256(payload));
        if(slot!=1 || outbox!=1)
            throw new IllegalStateException("Atomic Academic candidate/outbox write failed");
        return new Receipt(committedId,plan.key(),plan.classId(),
            plan.sessionId(),plan.sourceEventId(),plan.sourceRevision(),"STAGED");
    }
}

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

    public record Evidence(String actor,String key,String digest,String classId,
        String sessionId,long eventId,long revision,long academicGeneration,
        long attendanceGeneration,long schedulingGeneration,String commandId,
        String outboxHash) {}

    /**
     * Read all four Academic-local pieces for one actor/key without mutating
     * anything. An incomplete local transaction is NOT treated as absence:
     * it is returned as divergent evidence and blocks any automatic recovery.
     */
    public record LocalCandidate(Evidence evidence,boolean slotMatches,
        boolean outboxMatches,boolean handoffPresent,boolean handoffMatches) {}

    public LocalCandidate inspectCandidate(String actor,String requestKey) {
        List<LocalCandidate> rows=db.query(
            "SELECT c.operator_username,c.request_key,c.request_hash,c.class_id,"+
            "c.class_session_id,c.source_event_id,c.source_revision,"+
            "c.academic_generation,c.attendance_generation,c.scheduling_generation,"+
            "c.command_id::text,o.payload_hash,"+
            "COALESCE(s.class_id=c.class_id AND "+
            "s.class_session_id=c.class_session_id AND "+
            "s.source_event_id=c.source_event_id AND "+
            "s.source_revision=c.source_revision,FALSE),"+
            "COALESCE(o.event_kind='REHEARSAL_ONLY',FALSE),"+
            "(r.command_id IS NOT NULL),"+
            "COALESCE(r.actor_username=c.operator_username AND "+
            "r.request_key=c.request_key AND "+
            "r.core_outbox_hash=o.payload_hash,FALSE) "+
            "FROM academic_session_candidate_commands c "+
            "LEFT JOIN academic_session_candidate_slots s ON s.command_id=c.command_id "+
            "LEFT JOIN academic_session_candidate_outbox o ON o.command_id=c.command_id "+
            "LEFT JOIN academic_candidate_handoff_receipts r ON r.command_id=c.command_id "+
            "WHERE c.operator_username=? AND c.request_key=?",
            (rs,i)->new LocalCandidate(new Evidence(
                rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),
                rs.getLong(7),rs.getLong(8),rs.getLong(9),
                rs.getLong(10),rs.getString(11),rs.getString(12)),
                rs.getBoolean(13),rs.getBoolean(14),
                rs.getBoolean(15),rs.getBoolean(16)),actor,requestKey);
        if(rows.size()>1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicate Academic candidate identity in local database");
        return rows.isEmpty()?null:rows.get(0);
    }

    /**
     * All staged timetable commands remain REHEARSAL_ONLY. There is no
     * independently authorized promotion or audited disposal protocol yet.
     * Therefore even a RELEASED Core claim is NOT evidence that these
     * commands were applied as domain mutations. Strict writer cutover
     * must refuse any such outstanding intent, including acknowledged rows.
     */
    public long unpromotedRehearsalCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_candidate_commands",
            Long.class);
        if(count==null || count<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session rehearsal ledger unavailable");
        return count;
    }

    /** Durable local proof, read only AFTER the @Transactional stage commits. */
    private record SignedEvent(Evidence evidence,String canonical,
        String storedJson,String action,String date,String from,String to,
        String status) {}

    /**
     * Verify the exact ORIGINAL canonical event bytes, the PostgreSQL
     * JSONB copy and every typed session value before a Core release.
     * A historic event lacking canonical bytes is an unresolved rehearsal,
     * NEVER an implicit authorization to hand back the writer.
     */
    public Evidence committedEvidence(String actor,String key,String commandId) {
        List<SignedEvent> rows=db.query(
            "SELECT c.operator_username,c.request_key,c.request_hash,c.class_id,"+
            "c.class_session_id,c.source_event_id,c.source_revision,"+
            "c.academic_generation,c.attendance_generation,c.scheduling_generation,"+
            "c.command_id::text,o.payload_hash,o.payload_canonical,o.payload::text,"+
            "c.action,s.session_date::text,s.start_time::text,s.end_time::text,"+
            "s.status "+
            "FROM academic_session_candidate_commands c "+
            "JOIN academic_session_candidate_slots s ON s.command_id=c.command_id "+
            "JOIN academic_session_candidate_outbox o ON o.command_id=c.command_id "+
            "WHERE c.operator_username=? AND c.request_key=? "+
            "AND c.command_id=CAST(? AS UUID) AND c.state='STAGED' "+
            "AND s.class_id=c.class_id AND s.class_session_id=c.class_session_id "+
            "AND s.source_event_id=c.source_event_id "+
            "AND s.source_revision=c.source_revision AND o.event_kind='REHEARSAL_ONLY'",
            (rs,i)->new SignedEvent(new Evidence(
                rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),
                rs.getLong(7),rs.getLong(8),rs.getLong(9),rs.getLong(10),
                rs.getString(11),rs.getString(12)),
                rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getString(16),rs.getString(17),rs.getString(18),
                rs.getString(19)),
            actor,key,commandId);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic candidate has no atomic durable command, slot and outbox");
        var row=rows.get(0);
        var proof=row.evidence();
        if(proof.outboxHash()==null ||
            !proof.outboxHash().matches("[0-9a-f]{64}") ||
            row.canonical()==null || row.canonical().isBlank() ||
            row.storedJson()==null || row.action()==null ||
            row.status()==null || row.date()==null ||
            row.from()==null || row.to()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session candidate canonical outbox evidence missing");
        final ObjectNode expected=json.createObjectNode();
        final com.fasterxml.jackson.databind.JsonNode stored;
        try {
            var date=LocalDate.parse(row.date());
            var from=LocalTime.parse(row.from());
            var to=LocalTime.parse(row.to());
            if(!to.isAfter(from) ||
                !("RESCHEDULE".equals(row.action()) &&
                    "PLANNED".equals(row.status()) ||
                  "CANCEL".equals(row.action()) &&
                    "CANCELLED".equals(row.status())))
                throw new IllegalArgumentException("Invalid session candidate slot");
            expected.put("commandId",proof.commandId());
            expected.put("operator",proof.actor());
            expected.put("requestKey",proof.key());
            expected.put("classId",proof.classId());
            expected.put("sessionId",proof.sessionId());
            expected.put("sourceEventId",proof.eventId());
            expected.put("sourceRevision",proof.revision());
            expected.put("action",row.action());
            expected.put("date",date.toString());
            expected.put("startTime",from.toString());
            expected.put("endTime",to.toString());
            expected.put("status",row.status());
            expected.put("academicGeneration",proof.academicGeneration());
            expected.put("attendanceGeneration",proof.attendanceGeneration());
            expected.put("schedulingGeneration",proof.schedulingGeneration());
            stored=json.readTree(row.storedJson());
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Invalid typed Academic session candidate source evidence",ex);
        }
        if(!expected.toString().equals(row.canonical()) ||
            !AcademicSessionSourceRepository.sha256(row.canonical())
                .equals(proof.outboxHash()) ||
            stored==null || !stored.isObject() ||
            stored.size()!=expected.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session candidate canonical SHA-256 or JSONB copy diverged");
        for(var iter=expected.fields();iter.hasNext();) {
            var entry=iter.next();
            var actual=stored.get(entry.getKey());
            var wanted=entry.getValue();
            if(actual==null || wanted.isTextual()!=actual.isTextual() ||
                wanted.isNumber()!=actual.isNumber() ||
                !wanted.asText().equals(actual.asText()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Academic session candidate payload differs from immutable receipt");
        }
        return proof;
    }

    /**
     * Bounded restart-safe listing of commits which have NO confirmed local
     * Core-claim release receipt. Never auto-release a claim unless all three
     * immutable local records are still present and match the original key.
     */
    public List<Evidence> pendingHandoffs(int limit) {
        if(limit<1 || limit>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic handback recovery batch must contain 1..50 rows");
        return db.query(
            "SELECT c.operator_username,c.request_key,c.request_hash,c.class_id,"+
            "c.class_session_id,c.source_event_id,c.source_revision,"+
            "c.academic_generation,c.attendance_generation,c.scheduling_generation,"+
            "c.command_id::text,o.payload_hash "+
            "FROM academic_session_candidate_commands c "+
            "JOIN academic_session_candidate_slots s ON s.command_id=c.command_id "+
            "JOIN academic_session_candidate_outbox o ON o.command_id=c.command_id "+
            "LEFT JOIN academic_candidate_handoff_receipts r ON r.command_id=c.command_id "+
            "WHERE r.command_id IS NULL AND c.state='STAGED' "+
            "AND s.class_id=c.class_id AND s.class_session_id=c.class_session_id "+
            "AND s.source_event_id=c.source_event_id "+
            "AND s.source_revision=c.source_revision "+
            "AND o.event_kind='REHEARSAL_ONLY' "+
            "ORDER BY c.created_at,c.operator_username,c.request_key LIMIT ?",
            (rs,i)->new Evidence(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),rs.getLong(7),
                rs.getLong(8),rs.getLong(9),rs.getLong(10),rs.getString(11),
                rs.getString(12)),limit);
    }

    /** Only call AFTER the matching Core release has committed over HTTP. */
    public void recordHandoff(Evidence committed) {
        if(committed==null || committed.commandId()==null ||
            committed.outboxHash()==null ||
            !committed.outboxHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot record an unverified Core candidate release");
        int rows=db.update(
            "INSERT INTO academic_candidate_handoff_receipts "+
            "(command_id,actor_username,request_key,core_outbox_hash) "+
            "SELECT c.command_id,c.operator_username,c.request_key,o.payload_hash "+
            "FROM academic_session_candidate_commands c "+
            "JOIN academic_session_candidate_slots s ON s.command_id=c.command_id "+
            "JOIN academic_session_candidate_outbox o ON o.command_id=c.command_id "+
            "WHERE c.command_id=CAST(? AS UUID) AND c.operator_username=? "+
            "AND c.request_key=? AND c.request_hash=? AND c.state='STAGED' "+
            "AND c.class_id=? AND c.class_session_id=? "+
            "AND c.source_event_id=? AND c.source_revision=? "+
            "AND c.academic_generation=? AND c.attendance_generation=? "+
            "AND c.scheduling_generation=? AND o.payload_hash=? "+
            "AND o.event_kind='REHEARSAL_ONLY' "+
            "AND s.class_session_id=c.class_session_id "+
            "AND s.source_revision=c.source_revision "+
            "ON CONFLICT(command_id) DO NOTHING",
            committed.commandId(),committed.actor(),committed.key(),
            committed.digest(),committed.classId(),committed.sessionId(),
            committed.eventId(),committed.revision(),
            committed.academicGeneration(),committed.attendanceGeneration(),
            committed.schedulingGeneration(),committed.outboxHash());
        if(rows==0) {
            Long exact=db.queryForObject(
                "SELECT COUNT(*) FROM academic_candidate_handoff_receipts "+
                "WHERE command_id=CAST(? AS UUID) AND actor_username=? "+
                "AND request_key=? AND core_outbox_hash=?",
                Long.class,committed.commandId(),committed.actor(),
                committed.key(),committed.outboxHash());
            if(!Long.valueOf(1).equals(exact))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Academic local handoff evidence does not match Core release");
        } else if(rows!=1) {
            throw new IllegalStateException("Ambiguous Academic candidate release receipt");
        }
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
            "(outbox_id,command_id,event_kind,payload,payload_hash,payload_canonical) "+
            "VALUES(CAST(? AS UUID),CAST(? AS UUID),'REHEARSAL_ONLY',"+
            "CAST(? AS JSONB),?,?)",
            UUID.randomUUID().toString(),committedId,payload,
            AcademicSessionSourceRepository.sha256(payload),payload);
        if(slot!=1 || outbox!=1)
            throw new IllegalStateException("Atomic Academic candidate/outbox write failed");
        return new Receipt(committedId,plan.key(),plan.classId(),
            plan.sessionId(),plan.sourceEventId(),plan.sourceRevision(),"STAGED");
    }
}

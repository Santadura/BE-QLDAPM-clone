package vn.iig.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/**
 * An independent Academic PostgreSQL transaction owns three durable facts:
 * command, proposed typed source JSON, and immutable REHEARSAL_ONLY outbox.
 * Neither the Core source nor the Academic imported/shadow rows are mutated.
 * The service calling stage MUST hold a Spring @Transactional boundary.
 */
@Repository
public class AcademicRosterCandidateRepository {
    public record Plan(String actor,String key,String digest,String entityType,
        String entityId,long sourceEventId,long sourceRevision,
        long writerGeneration,String action,BigDecimal proposedValue) {}
    public record Receipt(String commandId,String requestKey,String entityType,
        String entityId,long sourceEventId,String state) {}

    private record Source(String studentId,long eventId,long revision,
        String kind,String payload) {}
    private record Stored(String commandId,String hash,String type,String id,
        long eventId,long revision,long generation,String action,
        BigDecimal proposedValue,long payloadCount,long outboxCount) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    public AcademicRosterCandidateRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;this.json=json;
    }
    private static ResponseStatusException conflict(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT,text);
    }

    public Receipt stage(Plan plan) {
        String table="TARGET".equals(plan.entityType())
            ? "academic_student_targets_shadow"
            : "academic_student_results_shadow";
        String column="TARGET".equals(plan.entityType())
            ? "student_target_id" : "student_result_id";
        List<Source> found=db.query(
            "SELECT student_id,source_event_id,source_revision,event_kind,"+
            "source_payload::text FROM "+table+" WHERE "+column+"=? FOR UPDATE",
            (rs,i)->new Source(rs.getString(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4),rs.getString(5)),plan.entityId());
        if(found.size()!=1 || !"UPSERT".equals(found.get(0).kind()) ||
            found.get(0).eventId()!=plan.sourceEventId() ||
            found.get(0).revision()!=plan.sourceRevision())
            throw conflict("Candidate refers to a missing or stale Core target/result");

        final ObjectNode proposed;
        try {
            proposed=(ObjectNode)json.readTree(found.get(0).payload()).deepCopy();
        } catch(Exception ex) {
            throw conflict("Corrupted Academic source JSON for candidate");
        }
        String identity=proposed.path(column).asText("");
        if(!plan.entityId().equals(identity) ||
            !found.get(0).studentId().equals(proposed.path("student_id").asText("")))
            throw conflict("Academic target/result source identity differs");
        if("UPSERT".equals(plan.action()))
            proposed.put("TARGET".equals(plan.entityType())?"target_value":"score",
                plan.proposedValue());

        String commandId=UUID.randomUUID().toString();
        final int inserted;
        try {
            inserted=db.update(
                "INSERT INTO academic_roster_candidate_commands "+
                "(command_id,operator_username,request_key,request_hash,entity_type,"+
                "entity_id,source_event_id,source_revision,writer_generation,action) "+
                "VALUES(CAST(? AS UUID),?,?,?,?,?,?,?,?,?) "+
                "ON CONFLICT(operator_username,request_key) DO NOTHING",
                commandId,plan.actor(),plan.key(),plan.digest(),plan.entityType(),
                plan.entityId(),plan.sourceEventId(),plan.sourceRevision(),
                plan.writerGeneration(),plan.action());
        } catch(DuplicateKeyException ex) {
            throw conflict("A conflicting command already owns this exact source version");
        }
        List<Stored> rows=db.query(
            "SELECT c.command_id::text,c.request_hash,c.entity_type,c.entity_id,"+
            "c.source_event_id,c.source_revision,c.writer_generation,c.action,"+
            "p.proposed_value,COUNT(DISTINCT p.command_id),COUNT(DISTINCT o.command_id) "+
            "FROM academic_roster_candidate_commands c "+
            "LEFT JOIN academic_roster_candidate_payloads p ON p.command_id=c.command_id "+
            "LEFT JOIN academic_roster_candidate_outbox o ON o.command_id=c.command_id "+
            "WHERE c.operator_username=? AND c.request_key=? "+
            "GROUP BY c.command_id,c.request_hash,c.entity_type,c.entity_id,"+
            "c.source_event_id,c.source_revision,c.writer_generation,c.action,p.proposed_value",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),
                rs.getString(8),rs.getBigDecimal(9),rs.getLong(10),rs.getLong(11)),
            plan.actor(),plan.key());
        if(rows.size()!=1)
            throw conflict("Academic candidate command could not be proved");
        Stored committed=rows.get(0);
        if(!plan.digest().equals(committed.hash()) ||
            !plan.entityType().equals(committed.type()) ||
            !plan.entityId().equals(committed.id()) ||
            plan.sourceEventId()!=committed.eventId() ||
            plan.sourceRevision()!=committed.revision() ||
            plan.writerGeneration()!=committed.generation() ||
            !plan.action().equals(committed.action()))
            throw conflict("Candidate idempotency key conflicts with prior evidence");

        if(inserted==0) {
            // A retried command is ONLY successful if both companion facts
            // committed. A partial historical row is never silently healed.
            if(committed.payloadCount()!=1 || committed.outboxCount()!=1 ||
                (plan.proposedValue()==null
                    ? committed.proposedValue()!=null
                    : committed.proposedValue()==null ||
                      committed.proposedValue().compareTo(plan.proposedValue())!=0))
                throw conflict("Academic target/result command lacks exact durable outbox");
            return new Receipt(committed.commandId(),plan.key(),plan.entityType(),
                plan.entityId(),plan.sourceEventId(),"STAGED");
        }
        if(inserted!=1)
            throw new IllegalStateException("Ambiguous Academic roster candidate admission");

        int payload=db.update(
            "INSERT INTO academic_roster_candidate_payloads "+
            "(command_id,proposed_payload,proposed_value) "+
            "VALUES(CAST(? AS UUID),CAST(? AS JSONB),?)",
            committed.commandId(),proposed.toString(),plan.proposedValue());

        ObjectNode outbox=json.createObjectNode();
        outbox.put("commandId",committed.commandId());
        outbox.put("operator",plan.actor());
        outbox.put("requestKey",plan.key());
        outbox.put("entityType",plan.entityType());
        outbox.put("entityId",plan.entityId());
        outbox.put("sourceEventId",plan.sourceEventId());
        outbox.put("sourceRevision",plan.sourceRevision());
        outbox.put("writerGeneration",plan.writerGeneration());
        outbox.put("action",plan.action());
        outbox.set("proposedPayload",proposed);
        String event=outbox.toString();
        int staged=db.update(
            "INSERT INTO academic_roster_candidate_outbox "+
            "(outbox_id,command_id,event_kind,payload,payload_hash) "+
            "VALUES(CAST(? AS UUID),CAST(? AS UUID),'REHEARSAL_ONLY',CAST(? AS JSONB),?)",
            UUID.randomUUID().toString(),committed.commandId(),event,
            AcademicSessionSourceRepository.sha256(event));
        if(payload!=1 || staged!=1)
            throw new IllegalStateException(
                "Academic candidate payload and outbox must commit atomically");
        return new Receipt(committed.commandId(),plan.key(),plan.entityType(),
            plan.entityId(),plan.sourceEventId(),"STAGED");
    }
}

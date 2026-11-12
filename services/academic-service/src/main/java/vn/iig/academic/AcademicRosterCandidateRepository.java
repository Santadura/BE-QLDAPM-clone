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

    /**
     * Immutable local evidence, strictly verified BEFORE any remote Core
     * release. JSONB is compared structurally; SHA256 is verified from the
     * original canonical byte string (never a reserialized JSONB document).
     */
    public record Evidence(String actor,String key,String digest,
        String entityType,String entityId,long eventId,long revision,
        long writerGeneration,String action,String commandId,String outboxHash) {}
    private record EvidenceRow(Evidence evidence,String proposedPayload,
        BigDecimal value,String canonical,String jsonbPayload) {}
    private record Pending(String actor,String key,String commandId) {}

    /**
     * Read-only local side of an independent Core/Academic claim comparison.
     * Invalid or incomplete local facts are reported as nonmatching evidence,
     * not repaired or released. Never fabricate a command for a Core orphan.
     */
    public record LocalInspection(String commandId,boolean hasOutbox,
        boolean hasHandoffReceipt,boolean hasDisposition,
        boolean handoffMatches,boolean dispositionMatches,
        Evidence evidence) {}
    private record LocalRow(String commandId,boolean outbox,boolean handoff,
        String handoffHash,boolean disposition,String dispositionHash) {}

    public LocalInspection inspectCandidate(String actor,String requestKey) {
        var rows=db.query(
            "SELECT c.command_id::text,o.command_id IS NOT NULL,"+
            "h.command_id IS NOT NULL,h.core_outbox_hash,"+
            "d.command_id IS NOT NULL,d.outbox_hash "+
            "FROM academic_roster_candidate_commands c "+
            "LEFT JOIN academic_roster_candidate_outbox o ON o.command_id=c.command_id "+
            "LEFT JOIN academic_roster_candidate_handoff_receipts h "+
            "ON h.command_id=c.command_id "+
            "LEFT JOIN academic_roster_candidate_dispositions d "+
            "ON d.command_id=c.command_id "+
            "WHERE c.operator_username=? AND c.request_key=?",
            (rs,i)->new LocalRow(rs.getString(1),rs.getBoolean(2),
                rs.getBoolean(3),rs.getString(4),
                rs.getBoolean(5),rs.getString(6)),actor,requestKey);
        if(rows.isEmpty())return null;
        if(rows.size()!=1)
            throw conflict("Duplicate Academic roster command identity");
        var row=rows.get(0);
        Evidence exact=null;
        try {
            exact=committedEvidence(actor,requestKey,row.commandId());
        } catch(ResponseStatusException ex) {
            if(ex.getStatusCode().value()!=409)throw ex;
            // Report incomplete/damaged local evidence for manual triage.
        }
        return new LocalInspection(row.commandId(),row.outbox(),
            row.handoff(),row.disposition(),
            exact!=null && row.handoff() &&
                exact.outboxHash().equals(row.handoffHash()),
            exact!=null && row.disposition() &&
                exact.outboxHash().equals(row.dispositionHash()),exact);
    }

    public Evidence committedEvidence(String actor,String key,String commandId) {
        List<EvidenceRow> rows=db.query(
            "SELECT c.operator_username,c.request_key,c.request_hash,c.entity_type,"+
            "c.entity_id,c.source_event_id,c.source_revision,c.writer_generation,"+
            "c.action,c.command_id::text,o.payload_hash,p.proposed_payload::text,"+
            "p.proposed_value,o.payload_canonical,o.payload::text "+
            "FROM academic_roster_candidate_commands c "+
            "JOIN academic_roster_candidate_payloads p ON p.command_id=c.command_id "+
            "JOIN academic_roster_candidate_outbox o ON o.command_id=c.command_id "+
            "WHERE c.operator_username=? AND c.request_key=? "+
            "AND c.command_id=CAST(? AS UUID) AND c.state='STAGED' "+
            "AND o.event_kind='REHEARSAL_ONLY'",
            (rs,i)->new EvidenceRow(new Evidence(rs.getString(1),
                rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),
                rs.getString(9),rs.getString(10),rs.getString(11)),
                rs.getString(12),rs.getBigDecimal(13),rs.getString(14),
                rs.getString(15)),actor,key,commandId);
        if(rows.size()!=1)
            throw conflict("No complete immutable Academic candidate/outbox pair");
        EvidenceRow row=rows.get(0);
        Evidence proof=row.evidence();
        if(row.canonical()==null || proof.outboxHash()==null ||
            !proof.outboxHash().matches("[0-9a-f]{64}") ||
            !AcademicSessionSourceRepository.sha256(row.canonical())
                .equals(proof.outboxHash()))
            throw conflict("Academic roster outbox canonical SHA256 evidence missing");

        final com.fasterxml.jackson.databind.JsonNode raw;
        final com.fasterxml.jackson.databind.JsonNode stored;
        final com.fasterxml.jackson.databind.JsonNode proposed;
        try {
            raw=json.readTree(row.canonical());
            stored=json.readTree(row.jsonbPayload());
            proposed=json.readTree(row.proposedPayload());
        } catch(Exception ex) {
            throw conflict("Committed Academic outbox contains invalid JSON");
        }
        if(raw==null || !raw.isObject() || !raw.equals(stored) ||
            !raw.path("proposedPayload").equals(proposed) ||
            !proof.commandId().equals(raw.path("commandId").asText("")) ||
            !proof.actor().equals(raw.path("operator").asText("")) ||
            !proof.key().equals(raw.path("requestKey").asText("")) ||
            !proof.entityType().equals(raw.path("entityType").asText("")) ||
            !proof.entityId().equals(raw.path("entityId").asText("")) ||
            proof.eventId()!=raw.path("sourceEventId").asLong(-1) ||
            proof.revision()!=raw.path("sourceRevision").asLong(-1) ||
            proof.writerGeneration()!=raw.path("writerGeneration").asLong(-1) ||
            !proof.action().equals(raw.path("action").asText("")))
            throw conflict("Academic roster candidate command and outbox disagree");
        String numericField="TARGET".equals(proof.entityType())
            ? "target_value" : "score";
        if(!proof.entityId().equals(proposed.path(
                "TARGET".equals(proof.entityType())?"student_target_id":"student_result_id"
                ).asText("")) ||
            !proposed.path("student_id").isTextual() ||
            ("UPSERT".equals(proof.action()) &&
                (row.value()==null ||
                 !proposed.path(numericField).isNumber() ||
                 proposed.path(numericField).decimalValue().compareTo(row.value())!=0)) ||
            ("DELETE".equals(proof.action()) && row.value()!=null))
            throw conflict("Proposed Academic target/result value differs from receipt");
        String canonicalRequest="academic-roster-candidate-v1\n"+
            proof.entityType()+"\n"+proof.entityId()+"\n"+
            proof.eventId()+"\n"+proof.revision()+"\n"+
            proof.action()+"\n"+
            (row.value()==null?"DELETE":row.value().toPlainString());
        if(!AcademicSessionSourceRepository.sha256(canonicalRequest)
                .equals(proof.digest()))
            throw conflict("Academic candidate source request digest differs");
        return proof;
    }

    public java.util.List<Evidence> pendingHandoffs(int limit) {
        if(limit<1 || limit>100)
            throw new IllegalArgumentException("Roster handoff scan budget 1..100");
        var pending=db.query(
            "SELECT c.operator_username,c.request_key,c.command_id::text "+
            "FROM academic_roster_candidate_commands c "+
            "JOIN academic_roster_candidate_outbox o ON o.command_id=c.command_id "+
            "AND o.payload_canonical IS NOT NULL "+
            "LEFT JOIN academic_roster_candidate_handoff_receipts h "+
            "ON h.command_id=c.command_id "+
            "WHERE h.command_id IS NULL "+
            "ORDER BY c.created_at,c.command_id LIMIT ?",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),
                rs.getString(3)),limit);
        // Do not synthesize release evidence when one of the three durable
        // local facts is missing. A Core-only orphan is never queued here.
        return pending.stream()
            .map(p->committedEvidence(p.actor(),p.key(),p.commandId()))
            .toList();
    }

    public void recordHandoff(Evidence committed) {
        if(committed==null || committed.commandId()==null ||
            committed.outboxHash()==null)
            throw conflict("Missing Academic roster handback proof");
        int inserted=db.update(
            "INSERT INTO academic_roster_candidate_handoff_receipts "+
            "(command_id,actor_username,request_key,core_outbox_hash) "+
            "VALUES(CAST(? AS UUID),?,?,?) ON CONFLICT(command_id) DO NOTHING",
            committed.commandId(),committed.actor(),committed.key(),
            committed.outboxHash());
        if(inserted==0) {
            Long equal=db.queryForObject(
                "SELECT COUNT(*) FROM academic_roster_candidate_handoff_receipts "+
                "WHERE command_id=CAST(? AS UUID) AND actor_username=? "+
                "AND request_key=? AND core_outbox_hash=?",
                Long.class,committed.commandId(),committed.actor(),
                committed.key(),committed.outboxHash());
            if(!Long.valueOf(1).equals(equal))
                throw conflict("Academic roster handback receipt was changed");
        } else if(inserted!=1)
            throw new IllegalStateException("Ambiguous Academic roster handback receipt");
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
            "(outbox_id,command_id,event_kind,payload,payload_hash,payload_canonical) "+
            "VALUES(CAST(? AS UUID),CAST(? AS UUID),'REHEARSAL_ONLY',CAST(? AS JSONB),?,?)",
            UUID.randomUUID().toString(),committed.commandId(),event,
            AcademicSessionSourceRepository.sha256(event),event);
        if(payload!=1 || staged!=1)
            throw new IllegalStateException(
                "Academic candidate payload and outbox must commit atomically");
        return new Receipt(committed.commandId(),plan.key(),plan.entityType(),
            plan.entityId(),plan.sourceEventId(),"STAGED");
    }
}

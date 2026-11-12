package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core-owned, durable, PRIVATE lease-free claim for Target/Result candidate
 * handback. This is NOT Academic domain write permission.
 *
 * The immutable Core writer-control row is locked before admission and the
 * SQL trigger independently enforces that lock for direct SQL inserts.
 * Releasing a claim requires exact committed Academic command/outbox evidence.
 * A lost response may be retried; absent Academic evidence NEVER authorizes
 * release. Writer mode is NEVER changed by these endpoints.
 */
@RestController
@RequestMapping("/internal/academic/roster-candidate-claims")
@ConditionalOnProperty(name="iig.academic.roster-claims-enabled",havingValue="true")
public class AcademicRosterClaimBridge {
    public record Claim(String actor,String requestKey,String requestHash,
        String entityType,String entityId,long sourceEventId,
        long sourceRevision,long writerGeneration) {}
    public record Release(Claim claim,String candidateCommandId,String outboxHash) {}
    public record Result(String state,String actor,String requestKey) {}
    public record Summary(long openClaims,long releasedClaims,long auditEvents) {}

    private record Stored(String hash,String type,String id,long event,
        long revision,long generation,String state,String commandId,String outboxHash) {}
    private final JdbcTemplate db;
    private final byte[] secret;
    public AcademicRosterClaimBridge(JdbcTemplate db,
            @Value("${iig.academic.internal-key}") String privateKey) {
        if(privateKey==null || privateKey.isBlank())
            throw new IllegalArgumentException("Academic roster claim key required");
        this.db=db;
        this.secret=privateKey.getBytes(StandardCharsets.UTF_8);
    }

    private void requireKey(String key) {
        var provided=(key==null?"":key).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,provided))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic roster claim service key required");
    }
    private static void validate(Claim c) {
        if(c==null || c.actor()==null ||
            !c.actor().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            c.requestKey()==null ||
            !c.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            c.requestHash()==null || !c.requestHash().matches("[0-9a-f]{64}") ||
            c.entityType()==null ||
            !("TARGET".equals(c.entityType()) || "RESULT".equals(c.entityType())) ||
            c.entityId()==null ||
            !c.entityId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            c.sourceEventId()<1 || c.sourceRevision()<1 || c.writerGeneration()<2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Core roster candidate claim identity or source evidence invalid");
    }

    private Stored locked(Claim c) {
        List<Stored> rows=db.query(
            "SELECT request_hash,entity_type,entity_id,source_event_id,"+
            "source_revision,writer_generation,state,candidate_command_id::text,"+
            "candidate_outbox_hash FROM academic_roster_candidate_claims "+
            "WHERE actor_username=? AND request_key=? FOR UPDATE",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getString(7),
                rs.getString(8),rs.getString(9)),c.actor(),c.requestKey());
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Academic roster candidate claim missing");
        Stored row=rows.get(0);
        if(!c.requestHash().equals(row.hash()) ||
            !c.entityType().equals(row.type()) || !c.entityId().equals(row.id()) ||
            c.sourceEventId()!=row.event() ||
            c.sourceRevision()!=row.revision() ||
            c.writerGeneration()!=row.generation())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic roster candidate claim conflicts with original evidence");
        return row;
    }

    @GetMapping("/summary")
    @Transactional(readOnly=true)
    public Summary summary(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT (SELECT COUNT(*) FROM academic_roster_candidate_claims WHERE state='OPEN'),"+
            "(SELECT COUNT(*) FROM academic_roster_candidate_claims WHERE state='RELEASED'),"+
            "(SELECT COUNT(*) FROM academic_roster_candidate_claim_audit)",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
    }

    @PostMapping
    @Transactional
    public Result acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody Claim claim) {
        requireKey(supplied);
        validate(claim);
        List<String> fences=db.query(
            "SELECT writer_mode FROM academic_roster_writer_control "+
            "WHERE singleton=TRUE AND generation=? FOR SHARE",
            (rs,i)->rs.getString(1),claim.writerGeneration());
        if(fences.size()!=1 || !"QUIESCED".equals(fences.get(0)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core student roster is not frozen at requested generation");
        // Only claim a current immutable Core UPSERT (not a tombstone or
        // fabricated event). Its type/id/revision must agree exactly.
        Long live=db.queryForObject(
            "SELECT COUNT(*) FROM academic_roster_source_outbox e "+
            "WHERE e.event_id=? AND e.entity_type=? AND e.entity_id=? "+
            "AND e.source_revision=? AND e.event_kind='UPSERT' "+
            "AND NOT EXISTS(SELECT 1 FROM academic_roster_source_outbox next "+
            " WHERE next.entity_type=e.entity_type AND next.entity_id=e.entity_id "+
            " AND next.event_id>e.event_id)",
            Long.class,claim.sourceEventId(),claim.entityType(),
            claim.entityId(),claim.sourceRevision());
        if(!Long.valueOf(1).equals(live))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core roster claim requires latest immutable UPSERT source event");
        try {
            db.update(
                "INSERT INTO academic_roster_candidate_claims "+
                "(actor_username,request_key,request_hash,entity_type,entity_id,"+
                "source_event_id,source_revision,writer_generation) "+
                "VALUES(?,?,?,?,?,?,?,?) "+
                "ON CONFLICT(actor_username,request_key) DO NOTHING",
                claim.actor(),claim.requestKey(),claim.requestHash(),
                claim.entityType(),claim.entityId(),claim.sourceEventId(),
                claim.sourceRevision(),claim.writerGeneration());
        } catch(DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another unresolved candidate owns this Core roster entity",ex);
        }
        Stored row=locked(claim);
        return new Result(row.state(),claim.actor(),claim.requestKey());
    }

    @PostMapping("/release")
    @Transactional
    public Result release(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody Release release) {
        requireKey(supplied);
        if(release==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Missing committed roster candidate release");
        validate(release.claim());
        if(release.candidateCommandId()==null ||
            !release.candidateCommandId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
            release.outboxHash()==null || !release.outboxHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic roster command UUID and outbox digest required");
        Stored old=locked(release.claim());
        if("RELEASED".equals(old.state())) {
            if(!release.candidateCommandId().equalsIgnoreCase(old.commandId()) ||
                !release.outboxHash().equals(old.outboxHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Released Core roster claim cannot change receipt evidence");
            return new Result("RELEASED",release.claim().actor(),
                release.claim().requestKey());
        }
        if(!"OPEN".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Unknown Academic roster claim state");
        int changed=db.update(
            "UPDATE academic_roster_candidate_claims "+
            "SET state='RELEASED',candidate_command_id=CAST(? AS UUID),"+
            "candidate_outbox_hash=?,released_at=clock_timestamp() "+
            "WHERE actor_username=? AND request_key=? AND request_hash=? "+
            "AND state='OPEN'",
            release.candidateCommandId(),release.outboxHash(),
            release.claim().actor(),release.claim().requestKey(),
            release.claim().requestHash());
        if(changed!=1)
            throw new IllegalStateException("Core roster claim release not durable");
        return new Result("RELEASED",release.claim().actor(),
            release.claim().requestKey());
    }
}

package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private Core handback barrier for an Academic-owned candidate rehearsal.
 *
 * Acquire holds the Core writer-control row FOR SHARE before materializing a
 * claim: a racing CORE handback can never slip between a fence snapshot and
 * a committed OPEN claim. Core stays the only authoritative timetable writer.
 *
 * Release is deliberately a separate call AFTER the Academic PG transaction
 * committed; a lost response strands OPEN (safe), not a false authorization
 * to resume Core. Repeating a durable receipt is idempotent.
 */
@RestController
@RequestMapping("/internal/academic/candidate-claims")
@ConditionalOnProperty(name="iig.academic.candidate-claims-enabled",havingValue="true")
public class AcademicCandidateClaimBridge {
    public record Claim(String actor,String requestKey,String requestHash,
        String classId,String sessionId,long sourceEventId,long sourceRevision,
        long academicGeneration,long attendanceGeneration,long schedulingGeneration) {}
    public record Release(Claim claim,String candidateCommandId,String outboxHash) {}
    public record Result(String state,String actor,String requestKey) {}
    private record ClaimRow(String digest,String classId,String sessionId,
        long eventId,long revision,long academic,long attendance,long scheduling,
        String state,String commandId,String outboxHash) {}
    private record Fence(String mode,long generation) {}
    private final JdbcTemplate db;
    private final byte[] key;

    public AcademicCandidateClaimBridge(JdbcTemplate db,
            @Value("${iig.academic.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Academic candidate Core key required");
        this.db=db;
        this.key=secret.getBytes(StandardCharsets.UTF_8);
    }

    private void requireKey(String supplied) {
        byte[] received=(supplied==null?"":supplied).getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,received))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Private Academic candidate claim key required");
    }
    private static void validate(Claim c) {
        if(c==null || c.actor()==null ||
            !c.actor().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            c.requestKey()==null ||
            !c.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            c.requestHash()==null || !c.requestHash().matches("[0-9a-f]{64}") ||
            c.classId()==null ||
            !c.classId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            c.sessionId()==null ||
            !c.sessionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            c.sourceEventId()<1 || c.sourceRevision()<1 ||
            c.academicGeneration()<1 || c.attendanceGeneration()<1 ||
            c.schedulingGeneration()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Incomplete Academic claim identity, version or writer generation");
    }
    private ClaimRow lockedClaim(Claim claim) {
        List<ClaimRow> rows=db.query(
            "SELECT request_hash,class_id,class_session_id,source_event_id,"+
            "source_revision,academic_generation,attendance_generation,"+
            "scheduling_generation,state,candidate_command_id::text,candidate_outbox_hash "+
            "FROM academic_candidate_claims "+
            "WHERE actor_username=? AND request_key=? FOR UPDATE",
            (rs,i)->new ClaimRow(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),
                rs.getLong(7),rs.getLong(8),rs.getString(9),rs.getString(10),
                rs.getString(11)),claim.actor(),claim.requestKey());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Academic candidate Core claim not recorded");
        var row=rows.get(0);
        if(!claim.requestHash().equals(row.digest()) ||
            !claim.classId().equals(row.classId()) ||
            !claim.sessionId().equals(row.sessionId()) ||
            claim.sourceEventId()!=row.eventId() ||
            claim.sourceRevision()!=row.revision() ||
            claim.academicGeneration()!=row.academic() ||
            claim.attendanceGeneration()!=row.attendance() ||
            claim.schedulingGeneration()!=row.scheduling())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic Core claim evidence differs from original request");
        return row;
    }

    public record Summary(long openClaims,long releasedClaims,long auditEvents) {}
    @GetMapping("/summary")
    @Transactional(readOnly=true)
    public Summary summary(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied) {
        requireKey(supplied);
        return db.queryForObject(
            "SELECT (SELECT COUNT(*) FROM academic_candidate_claims WHERE state='OPEN'),"+
            "(SELECT COUNT(*) FROM academic_candidate_claims WHERE state='RELEASED'),"+
            "(SELECT COUNT(*) FROM academic_candidate_claim_audit)",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
    }

    @PostMapping
    @Transactional
    public Result acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody Claim claim) {
        requireKey(supplied);validate(claim);
        // Lock Academic first (same order as the Core Academic UPDATE
        // trigger), then the dependent Scheduling and Attendance modes.
        Fence a=db.queryForObject(
            "SELECT writer_mode,generation FROM academic_session_writer_control "+
            "WHERE singleton=TRUE FOR SHARE",
            (rs,i)->new Fence(rs.getString(1),rs.getLong(2)));
        Fence s=db.queryForObject(
            "SELECT writer_mode,writer_generation FROM scheduling_assignment_writer_control "+
            "WHERE singleton=TRUE FOR SHARE",
            (rs,i)->new Fence(rs.getString(1),rs.getLong(2)));
        Fence t=db.queryForObject(
            "SELECT writer_mode,generation FROM teacher_attendance_writer_control "+
            "WHERE singleton=TRUE FOR SHARE",
            (rs,i)->new Fence(rs.getString(1),rs.getLong(2)));
        if(a==null || s==null || t==null ||
            !"QUIESCED".equals(a.mode()) ||
            !"QUIESCED".equals(s.mode()) || !"QUIESCED".equals(t.mode()) ||
            a.generation()!=claim.academicGeneration() ||
            s.generation()!=claim.schedulingGeneration() ||
            t.generation()!=claim.attendanceGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic claim requires exact three frozen generations");

        // Verify this immutable event was imported and acknowledged, and
        // still describes the actual class-session source in Core.
        Long matching=db.queryForObject(
            "SELECT COUNT(*) FROM academic_session_source_outbox e "+
            "JOIN class_sessions c ON c.class_session_id=e.class_session_id "+
            "WHERE e.event_id=? AND e.class_session_id=? AND e.class_id=? "+
            "AND e.source_revision=? AND e.event_kind='UPSERT' "+
            "AND e.delivered_at IS NOT NULL AND e.payload IS NOT DISTINCT FROM to_jsonb(c)",
            Long.class,claim.sourceEventId(),claim.sessionId(),claim.classId(),
            claim.sourceRevision());
        if(!Long.valueOf(1).equals(matching))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic candidate source event changed or was not acknowledged");
        int created;
        try {
            created=db.update(
            "INSERT INTO academic_candidate_claims "+
            "(actor_username,request_key,request_hash,class_id,class_session_id,"+
            "source_event_id,source_revision,academic_generation,"+
            "attendance_generation,scheduling_generation) "+
            "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(actor_username,request_key) DO NOTHING",
            claim.actor(),claim.requestKey(),claim.requestHash(),claim.classId(),
            claim.sessionId(),claim.sourceEventId(),claim.sourceRevision(),
            claim.academicGeneration(),claim.attendanceGeneration(),
            claim.schedulingGeneration());
        } catch(DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another unresolved candidate holds the exact Academic lesson",ex);
        }
        ClaimRow existing=lockedClaim(claim);
        if(created==1) {
            db.update("INSERT INTO academic_candidate_claim_audit "+
                "(actor_username,request_key,request_hash,event_kind) "+
                "VALUES(?,?,?,'ACQUIRED')",
                claim.actor(),claim.requestKey(),claim.requestHash());
        }
        return new Result(existing.state(),claim.actor(),claim.requestKey());
    }

    @PostMapping("/release")
    @Transactional
    public Result release(
            @RequestHeader(value="X-Internal-Key",required=false) String supplied,
            @RequestBody Release release) {
        requireKey(supplied);
        if(release==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Missing candidate claim release");
        validate(release.claim());
        if(release.candidateCommandId()==null ||
            !release.candidateCommandId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
            release.outboxHash()==null ||
            !release.outboxHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact committed Academic command and outbox digest required");
        ClaimRow claim=lockedClaim(release.claim());
        if("RELEASED".equals(claim.state())) {
            if(!release.candidateCommandId().equalsIgnoreCase(claim.commandId()) ||
                !release.outboxHash().equals(claim.outboxHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Academic claim release receipt changed");
            return new Result("RELEASED",release.claim().actor(),release.claim().requestKey());
        }
        if(!"OPEN".equals(claim.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic claim cannot be released from unknown state");
        int n=db.update(
            "UPDATE academic_candidate_claims SET state='RELEASED',"+
            "candidate_command_id=CAST(? AS UUID),candidate_outbox_hash=?,"+
            "released_at=clock_timestamp() "+
            "WHERE actor_username=? AND request_key=? AND state='OPEN' "+
            "AND request_hash=?",
            release.candidateCommandId(),release.outboxHash(),
            release.claim().actor(),release.claim().requestKey(),
            release.claim().requestHash());
        if(n!=1)throw new IllegalStateException("Cannot persist Core handback release");
        db.update("INSERT INTO academic_candidate_claim_audit "+
            "(actor_username,request_key,request_hash,event_kind,"+
            "candidate_command_id,candidate_outbox_hash) "+
            "VALUES(?,?,?,'RELEASED',CAST(? AS UUID),?)",
            release.claim().actor(),release.claim().requestKey(),
            release.claim().requestHash(),release.candidateCommandId(),
            release.outboxHash());
        return new Result("RELEASED",release.claim().actor(),release.claim().requestKey());
    }
}

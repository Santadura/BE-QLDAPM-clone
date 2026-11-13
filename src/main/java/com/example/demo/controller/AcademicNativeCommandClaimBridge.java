package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * A private, DB-guarded Core claim for a frozen native Academic transaction.
 * A committed OPEN claim blocks Core writer/roster handback until an exact
 * immutable Academic command/outbox receipt is acknowledged separately.
 * No claim is ever auto-expired or treated as a rollback on HTTP timeout.
 */
@RestController
@RequestMapping("/internal/academic/native-command-claims")
@ConditionalOnProperty(name="iig.academic.native-command-claims-enabled",
    havingValue="true")
public class AcademicNativeCommandClaimBridge {
    public record Claim(String actorUsername,String classId,String operation,
        String requestKey,String requestHash,long sessionGeneration,
        long rosterGeneration,long attendanceGeneration,long schedulingGeneration) {}
    public record Release(Claim claim,String eventId,String outboxHash) {}
    public record Receipt(String state,String actorUsername,String classId,
        String operation,String requestKey,String eventId,String outboxHash) {}
    public record Summary(long openClaims,long releasedClaims,long auditEvents) {}
    private record Row(Claim identity,String state,String eventId,String outboxHash) {}

    private final JdbcTemplate db;
    private final byte[] secret;
    public AcademicNativeCommandClaimBridge(JdbcTemplate db,
            @Value("${iig.academic.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Academic native Core internal key required");
        this.db=db;this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authenticate(String provided) {
        if(!MessageDigest.isEqual(secret,
            (provided==null?"":provided).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Valid private native Academic claim key required");
    }
    private static void validate(Claim c) {
        if(c==null || c.actorUsername()==null ||
            !c.actorUsername().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            c.classId()==null ||
            !c.classId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            c.requestKey()==null ||
            !c.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            c.requestHash()==null || !c.requestHash().matches("[0-9a-f]{64}") ||
            !Set.of("PLAN","RESCHEDULE").contains(c.operation()) ||
            c.sessionGeneration()<1 || c.rosterGeneration()<1 ||
            c.attendanceGeneration()<1 || c.schedulingGeneration()<1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic command identity and four frozen epochs required");
    }

    private List<Row> rows(Claim c) {
        return db.query("SELECT class_id,request_hash,session_generation,"+
            "roster_generation,attendance_generation,scheduling_generation,"+
            "state,event_id::text,outbox_hash FROM academic_native_command_claims "+
            "WHERE actor_username=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->new Row(new Claim(c.actorUsername(),rs.getString(1),
                c.operation(),c.requestKey(),rs.getString(2),rs.getLong(3),
                rs.getLong(4),rs.getLong(5),rs.getLong(6)),
                rs.getString(7),rs.getString(8),rs.getString(9)),
            c.actorUsername(),c.operation(),c.requestKey());
    }
    private static Row exact(Claim claim,List<Row> rows) {
        if(rows.size()!=1 || !claim.equals(rows.get(0).identity()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Core claim has a different original actor, class, key or epoch");
        return rows.get(0);
    }
    private static Receipt reply(Row r) {
        Claim c=r.identity();
        return new Receipt(r.state(),c.actorUsername(),c.classId(),
            c.operation(),c.requestKey(),r.eventId(),r.outboxHash());
    }

    @PostMapping
    @Transactional
    public Receipt acquire(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestBody Claim claim) {
        authenticate(provided);validate(claim);
        var existing=rows(claim);
        if(!existing.isEmpty())return reply(exact(claim,existing));
        try {
            // PostgreSQL BEFORE INSERT locks all four Core writer controls;
            // this HTTP method must not substitute a stale preflight check.
            if(db.update("INSERT INTO academic_native_command_claims "+
                "(actor_username,class_id,operation,request_key,request_hash,"+
                "session_generation,roster_generation,attendance_generation,"+
                "scheduling_generation) VALUES(?,?,?,?,?,?,?,?,?)",
                claim.actorUsername(),claim.classId(),claim.operation(),
                claim.requestKey(),claim.requestHash(),claim.sessionGeneration(),
                claim.rosterGeneration(),claim.attendanceGeneration(),
                claim.schedulingGeneration())!=1)
                throw new IllegalStateException("Native Core claim not persisted");
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native command has a conflicting class claim or writer epoch",ex);
        }
        return reply(exact(claim,rows(claim)));
    }

    @PostMapping("/release")
    @Transactional
    public Receipt release(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestBody Release request) {
        authenticate(provided);
        if(request==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native command release proof required");
        validate(request.claim());
        if(request.eventId()==null || !request.eventId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
            request.outboxHash()==null ||
            !request.outboxHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic native event UUID and immutable hash required");
        Row old=exact(request.claim(),rows(request.claim()));
        if("RELEASED".equals(old.state())) {
            if(!request.eventId().equalsIgnoreCase(old.eventId()) ||
                !request.outboxHash().equals(old.outboxHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Native Core claim release proof changed on replay");
            return reply(old);
        }
        if(!"OPEN".equals(old.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Unexpected native command claim state");
        if(db.update("UPDATE academic_native_command_claims "+
            "SET state='RELEASED',event_id=CAST(? AS UUID),outbox_hash=?,"+
            "released_at=clock_timestamp() WHERE actor_username=? "+
            "AND operation=? AND request_key=? AND request_hash=? AND state='OPEN'",
            request.eventId(),request.outboxHash(),
            request.claim().actorUsername(),request.claim().operation(),
            request.claim().requestKey(),request.claim().requestHash())!=1)
            throw new IllegalStateException("Could not finish Core native claim release");
        return reply(exact(request.claim(),rows(request.claim())));
    }

    @GetMapping("/summary")
    @Transactional(readOnly=true)
    public Summary summary(
            @RequestHeader(value="X-Internal-Key",required=false) String provided) {
        authenticate(provided);
        Summary r=db.queryForObject(
            "SELECT (SELECT COUNT(*) FROM academic_native_command_claims "+
            "WHERE state='OPEN'),"+
            "(SELECT COUNT(*) FROM academic_native_command_claims WHERE state='RELEASED'),"+
            "(SELECT COUNT(*) FROM academic_native_command_claim_audit)",
            (rs,i)->new Summary(rs.getLong(1),rs.getLong(2),rs.getLong(3)));
        if(r==null || r.openClaims()<0 || r.releasedClaims()<0 ||
            r.auditEvents()!=r.openClaims()+2*r.releasedClaims())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core native command claim audit parity unavailable");
        return r;
    }
}

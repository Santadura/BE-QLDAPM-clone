package vn.iig.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable bind/renew/release for a STAGED teacher command and the Core
 * Academic session lease. Not an assignment executor: no public mutation
 * and no teacher/schedule write is authorized by acquiring this lease.
 *
 * A network-ambiguous reserve is safely retried with a deterministic
 * actor-scoped Core command key, not an arbitrary caller-controlled lease.
 * Both Core and Scheduling DBs must agree on the same session/versions.
 */
@Service
public class AcademicCommandLeaseService {
    public record Receipt(String requestKey,String classId,String sessionId,
        long classVersion,long sessionVersion,String leaseId,Instant expiresAt) {}
    private record Command(String state,String payload) {}
    private record Claim(String classId,String sessionId,long classVersion,
        long sessionVersion,String leaseId) {}
    private final JdbcTemplate db;
    private final SchedulingCoreClient core;
    private final ObjectMapper json;

    public AcademicCommandLeaseService(JdbcTemplate db,SchedulingCoreClient core,
                                        ObjectMapper json) {
        this.db=db;this.core=core;this.json=json;
    }

    static String scopedCoreCommandKey(String actor,String operation,String key) {
        try {
            byte[] canonical=("iig-academic-lease-v1\n"+actor+"\n"+operation+"\n"+key)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    private Command staged(String actor,String requestKey,boolean allowRejected) {
        if(actor==null || actor.isBlank() || requestKey==null ||
            !requestKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A staged teacher command key and actor are required");
        List<Command> rows=db.query(
            "SELECT state,payload::text FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' " +
            "AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2)),
            actor,requestKey);
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Staged teacher assignment command not found for the active TC");
        String state=rows.get(0).state();
        if(!"STAGED".equals(state) && !(allowRejected && "REJECTED".equals(state)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic lease cannot outlive a staged teacher assignment");
        return rows.get(0);
    }

    private record Expected(String classId,String sessionId,long classVersion,long sessionVersion) {}
    private Expected expected(Command command) {
        try {
            JsonNode envelope=json.readTree(command.payload());
            if(envelope==null || !"ASSIGN_TEACHER".equals(
                envelope.path("operation").asText()) ||
                !envelope.path("payload").isObject() ||
                !envelope.path("targetId").isNull())
                throw new IllegalArgumentException("Invalid teacher command envelope");
            JsonNode payload=envelope.path("payload");
            String classId=payload.path("classId").asText();
            String sessionId=payload.path("sessionId").asText();
            long cv=payload.path("classVersion").asLong(0);
            long sv=payload.path("sessionVersion").asLong(0);
            if(!classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                    !sessionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                    cv<1 || sv<1)
                throw new IllegalArgumentException("Invalid Academic evidence");
            return new Expected(classId,sessionId,cv,sv);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic class/session versions must be present in staged teacher command",ex);
        }
    }

    private List<Claim> claims(String actor,String key) {
        return db.query(
            "SELECT class_id,session_id,class_version,session_version,core_lease_id " +
            "FROM scheduling_academic_command_leases " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=? FOR UPDATE",
            (rs,i)->new Claim(rs.getString(1),rs.getString(2),
                rs.getLong(3),rs.getLong(4),rs.getString(5)),actor,key);
    }

    private Receipt check(String key,Expected expected,SchedulingCoreClient.AcademicLease live) {
        if(live==null || live.leaseId()==null || live.leaseId().isBlank() ||
                !expected.classId().equals(live.classId()) ||
                !expected.sessionId().equals(live.sessionId()) ||
                expected.classVersion()!=live.classVersion() ||
                expected.sessionVersion()!=live.sessionVersion() ||
                live.expiresAt()==null || !live.expiresAt().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic lease no longer proves the staged teacher session");
        return new Receipt(key,expected.classId(),expected.sessionId(),
            expected.classVersion(),expected.sessionVersion(),
            live.leaseId(),live.expiresAt());
    }

    @Transactional
    public Receipt acquire(String actor,String requestKey) {
        Expected wanted=expected(staged(actor,requestKey,false));
        List<Claim> prior=claims(actor,requestKey);
        if(prior.size()>1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicated Scheduling Academic command claim");
        if(!prior.isEmpty()) {
            Claim old=prior.get(0);
            if(!wanted.classId().equals(old.classId()) ||
                    !wanted.sessionId().equals(old.sessionId()) ||
                    wanted.classVersion()!=old.classVersion() ||
                    wanted.sessionVersion()!=old.sessionVersion())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Staged teacher evidence changed after Academic lease acquisition");
            Receipt receipt=check(requestKey,wanted,core.renewAcademicSession(old.leaseId()));
            db.update("UPDATE scheduling_academic_command_leases " +
                "SET expires_at=?,refreshed_at=NOW() WHERE actor_username=? " +
                "AND operation='ASSIGN_TEACHER' AND request_key=?",
                java.sql.Timestamp.from(receipt.expiresAt()),actor,requestKey);
            return receipt;
        }
        var live=core.reserveAcademicSession(wanted.classId(),wanted.sessionId(),
            wanted.classVersion(),wanted.sessionVersion(),
            scopedCoreCommandKey(actor,"ASSIGN_TEACHER",requestKey));
        Receipt receipt=check(requestKey,wanted,live);
        db.update(
            "INSERT INTO scheduling_academic_command_leases (" +
            "actor_username,operation,request_key,class_id,session_id," +
            "class_version,session_version,core_lease_id,expires_at) " +
            "VALUES(?,'ASSIGN_TEACHER',?,?,?,?,?,?,?,?)",
            actor,requestKey,wanted.classId(),wanted.sessionId(),
            wanted.classVersion(),wanted.sessionVersion(),receipt.leaseId(),
            java.sql.Timestamp.from(receipt.expiresAt()));
        return receipt;
    }

    @Transactional
    public Receipt renew(String actor,String requestKey) {
        Expected wanted=expected(staged(actor,requestKey,false));
        List<Claim> prior=claims(actor,requestKey);
        if(prior.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher assignment has no durably bound Academic lease");
        Claim current=prior.get(0);
        if(!wanted.classId().equals(current.classId()) ||
                !wanted.sessionId().equals(current.sessionId()) ||
                wanted.classVersion()!=current.classVersion() ||
                wanted.sessionVersion()!=current.sessionVersion())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Staged teacher evidence and Academic lease diverged");
        Receipt receipt=check(requestKey,wanted,
            core.renewAcademicSession(current.leaseId()));
        db.update("UPDATE scheduling_academic_command_leases " +
            "SET expires_at=?,refreshed_at=NOW() WHERE actor_username=? " +
            "AND operation='ASSIGN_TEACHER' AND request_key=?",
            java.sql.Timestamp.from(receipt.expiresAt()),actor,requestKey);
        return receipt;
    }

    @Transactional
    public void release(String actor,String requestKey) {
        // A command which locally committed a teacher/scheduling event may
        // not surrender its Academic lease without a compensation protocol.
        staged(actor,requestKey,true);
        List<Claim> rows=claims(actor,requestKey);
        if(rows.isEmpty())return;
        if(rows.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Duplicated Scheduling Academic command claim");
        core.releaseAcademicSession(rows.get(0).leaseId());
        db.update("DELETE FROM scheduling_academic_command_leases " +
            "WHERE actor_username=? AND operation='ASSIGN_TEACHER' AND request_key=?",
            actor,requestKey);
    }
}

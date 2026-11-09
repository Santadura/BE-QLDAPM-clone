package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transactional outbox for a FUTURE independent Scheduling assignment writer.
 *
 * There is deliberately no active producer or public delivery endpoint.
 * A later writer must change a schedule AND enqueue the immutable event in
 * the same Scheduling database transaction. Core's projection/attendance
 * reference consumer does not exist yet: activating delivery before that is
 * unsafe. Unique source/revision and an immutable payload digest make retries
 * detectable rather than silently replacing an event.
 */
@Repository
public class AssignmentDomainOutboxRepository {
    public record Event(String eventId,String actorUsername,String operation,
                        String requestKey,String sourceType,String scheduleId,
                        long revision,String eventKind,String payloadJson,
                        String payloadHash,int attemptCount) {}
    private record Existing(String eventId,String actorUsername,String operation,
                            String requestKey,String eventKind,String hash) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public AssignmentDomainOutboxRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;
        this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    }

    /**
     * MANDATORY means this cannot accidentally commit an event when the
     * associated domain mutation rolls back. The future command executor
     * owns the outer @Transactional transaction.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public String append(String actor,String operation,String requestKey,
                         String source,String scheduleId,long revision,
                         String kind,JsonNode payload) {
        if(actor==null || actor.isBlank() ||
           operation==null || operation.isBlank() ||
           requestKey==null || requestKey.isBlank() ||
           !Set.of("TEACHING","STAFF").contains(source) ||
           scheduleId==null || scheduleId.isBlank() || scheduleId.length()>50 ||
           revision<1 || !Set.of("UPSERT","TOMBSTONE").contains(kind) ||
           payload==null || !payload.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid assignment domain outbox event");

        // Lock the original command receipt. The caller must only emit events
        // while that command is still a valid STAGED candidate.
        List<String> receipts=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->rs.getString(1),actor,operation,requestKey);
        if(receipts.size()!=1 || !"STAGED".equals(receipts.get(0)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only a staged assignment command may create outbound events");

        String canonical;
        try {
            canonical=json.writeValueAsString(json.convertValue(payload,Object.class));
        } catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid assignment event payload",ex);
        }
        String digest=sha256(canonical);
        String eventId=UUID.randomUUID().toString();
        int count=db.update(
            "INSERT INTO scheduling_assignment_domain_outbox " +
            "(event_id,actor_username,operation,request_key,source_type," +
            "schedule_id,revision,event_kind,payload,payload_hash) " +
            "VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),?) " +
            "ON CONFLICT(source_type,schedule_id,revision) DO NOTHING",
            eventId,actor,operation,requestKey,source,scheduleId,revision,
            kind,canonical,digest);
        if(count==1) return eventId;

        List<Existing> existing=db.query(
            "SELECT event_id,actor_username,operation,request_key,event_kind,payload_hash " +
            "FROM scheduling_assignment_domain_outbox " +
            "WHERE source_type=? AND schedule_id=? AND revision=? FOR UPDATE",
            (rs,i)->new Existing(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),
            source,scheduleId,revision);
        if(existing.size()!=1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Assignment event disappeared after unique conflict");
        Existing prior=existing.get(0);
        if(!digest.equals(prior.hash()) || !kind.equals(prior.eventKind()) ||
            !actor.equals(prior.actorUsername()) ||
            !operation.equals(prior.operation()) ||
            !requestKey.equals(prior.requestKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Assignment revision already has a different outbound event");
        return prior.eventId();
    }

    public List<Event> pending(int limit) {
        if(limit<1 || limit>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid assignment outbox batch size");
        return db.query(
            "SELECT event_id,actor_username,operation,request_key,source_type," +
            "schedule_id,revision,event_kind,payload::text,payload_hash,attempt_count " +
            "FROM scheduling_assignment_domain_outbox " +
            "WHERE delivered_at IS NULL AND next_attempt_at<=NOW() " +
            "ORDER BY created_at,event_id LIMIT ?",
            (rs,i)->new Event(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),
                rs.getLong(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getInt(11)),limit);
    }

    /** Exact event+digest acknowledgement cannot skip a later revision. */
    public boolean acknowledge(String eventId,String hash) {
        if(eventId==null || eventId.isBlank() || hash==null ||
           !hash.matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid event ID and SHA-256 digest required");
        return db.update(
            "UPDATE scheduling_assignment_domain_outbox SET delivered_at=NOW()," +
            "last_error=NULL WHERE event_id=? AND payload_hash=? " +
            "AND delivered_at IS NULL",eventId,hash)==1;
    }

    /** Transient delivery failure is retained and retried; never discarded. */
    public void retryLater(String eventId,String reason) {
        if(eventId==null || eventId.isBlank() || eventId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid outbox event ID");
        String message=reason==null?"Unspecified delivery failure":
            reason.substring(0,Math.min(reason.length(),300));
        db.update(
            "UPDATE scheduling_assignment_domain_outbox SET " +
            "attempt_count=attempt_count+1,last_error=?," +
            "next_attempt_at=NOW()+interval '30 seconds' " +
            "WHERE event_id=? AND delivered_at IS NULL",
            message,eventId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 missing",ex);
        }
    }
}

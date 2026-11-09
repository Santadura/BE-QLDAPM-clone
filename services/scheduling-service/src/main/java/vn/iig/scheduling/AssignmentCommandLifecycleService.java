package vn.iig.scheduling;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local command state machine for future Scheduling write ownership.
 *
 * No public controller calls these transitions. The independent Scheduling
 * writer still MUST validate Academic reservations, business constraints and
 * maintain shadow/read rows before invoking markLocallyApplied in its SAME
 * database transaction. PENDING_REFERENCE is not business success: Core's
 * reference and teacher attendance/payroll consumers have not confirmed it.
 *
 * The future private Core relay may call confirmReference only AFTER durable
 * idempotent projection succeeds. This prevents acknowledging an outbound
 * event before the reference DB transaction committed.
 */
@Service
public class AssignmentCommandLifecycleService {
    private final JdbcTemplate db;

    public AssignmentCommandLifecycleService(JdbcTemplate db) {
        this.db=db;
    }

    /**
     * Mandatory caller transaction groups domain write, receipt state and
     * all source-revision outbox events in ONE local PostgreSQL commit.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public void markLocallyApplied(String actor,String operation,String key) {
        String state=lockedState(actor,operation,key);
        if(!"STAGED".equals(state))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only staged commands can enter reference projection");
        Long outgoing=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation=? AND request_key=?",
            Long.class,actor,operation,key);
        if(outgoing==null || outgoing==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scheduling domain changes must have an atomic outbox event");
        int changed=db.update(
            "UPDATE scheduling_assignment_command_inbox " +
            "SET state='PENDING_REFERENCE',updated_at=NOW() " +
            "WHERE actor_username=? AND operation=? AND request_key=? AND state='STAGED'",
            actor,operation,key);
        if(changed!=1)
            throw new IllegalStateException("Could not advance staged command");
    }

    /**
     * At-least-once Core callback; a replay is harmless. For batch commands
     * the receipt advances to COMMITTED only when ALL immutable events were
     * confirmed. A failed Core projection leaves the event pending for retry.
     */
    @Transactional
    public boolean confirmReference(String actor,String operation,String key,
                                    String eventId,String hash) {
        String state=lockedState(actor,operation,key);
        if(!"PENDING_REFERENCE".equals(state) && !"COMMITTED".equals(state))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Assignment command has not reached a durable local commit");
        Long matching=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE event_id=? AND payload_hash=? AND actor_username=? " +
            "AND operation=? AND request_key=?",
            Long.class,eventId,hash,actor,operation,key);
        if(matching==null || matching!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core acknowledged unknown assignment event or wrong digest");

        db.update("UPDATE scheduling_assignment_domain_outbox " +
            "SET delivered_at=NOW(),last_error=NULL " +
            "WHERE event_id=? AND payload_hash=? AND delivered_at IS NULL",
            eventId,hash);
        Long outstanding=db.queryForObject(
            "SELECT count(*) FROM scheduling_assignment_domain_outbox " +
            "WHERE actor_username=? AND operation=? AND request_key=? " +
            "AND delivered_at IS NULL",
            Long.class,actor,operation,key);
        if(outstanding==null) throw new IllegalStateException("Outbox count unavailable");
        if(outstanding>0) return false;
        if("PENDING_REFERENCE".equals(state)) {
            int changed=db.update(
                "UPDATE scheduling_assignment_command_inbox " +
                "SET state='COMMITTED',updated_at=NOW() " +
                "WHERE actor_username=? AND operation=? AND request_key=? " +
                "AND state='PENDING_REFERENCE'",
                actor,operation,key);
            if(changed!=1) throw new IllegalStateException("Cannot finish assignment command");
        }
        return true;
    }

    private String lockedState(String actor,String operation,String key) {
        if(actor==null || actor.isBlank() || operation==null || operation.isBlank() ||
           key==null || key.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Actor, operation and command key required");
        List<String> states=db.query(
            "SELECT state FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->rs.getString(1),actor,operation,key);
        if(states.size()!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Assignment command receipt not found");
        return states.get(0);
    }
}

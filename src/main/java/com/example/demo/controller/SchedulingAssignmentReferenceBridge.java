package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 8d: DISABLED-BY-DEFAULT private Core reference projection consumer.
 *
 * This is NOT a public assignment writer. It may mirror only standalone
 * non-class CS/SALE shifts from a future Scheduling command outbox, and only
 * while Core's own assignment tables are QUIESCED. Teaching and class-support
 * writes require Academic/attendance sagas and remain unsupported.
 *
 * The event ID, revision and canonical JSON digest are durable in Core's
 * reference receipt transaction. Replayed HTTP deliveries never duplicate
 * changes. No Core outbox feedback is emitted for a Scheduling-origin update.
 */
@RestController
@RequestMapping("/internal/scheduling/assignments/reference")
@ConditionalOnProperty(name="iig.scheduling.reference-writes-enabled",havingValue="true")
public class SchedulingAssignmentReferenceBridge {
    public record Event(String eventId,String actorUsername,String operation,
                        String requestKey,String sourceType,String scheduleId,
                        long revision,String eventKind,String payloadJson,
                        String payloadHash) {}
    public record Receipt(String eventId,long revision,String outcome) {}
    private record Previous(long revision,String eventId,String hash) {}
    private record ExistingShift(String employeeId,String classId,String workplaceId,
            LocalDate date,LocalTime start,LocalTime end,String workType,String status) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final byte[] secret;

    public SchedulingAssignmentReferenceBridge(JdbcTemplate db,ObjectMapper json,
            @Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.db=db;
        this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    @Transactional
    public Receipt apply(@RequestHeader(value="X-Internal-Key",required=false) String supplied,
                         @RequestBody Event event) {
        byte[] actual=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,actual))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid Scheduling reference bridge key");
        if(event==null || event.eventId()==null || event.eventId().isBlank() ||
                event.eventId().length()>50 || event.scheduleId()==null ||
                event.scheduleId().isBlank() || event.scheduleId().length()>50 ||
                event.actorUsername()==null || event.actorUsername().isBlank() ||
                event.operation()==null || event.operation().isBlank() ||
                event.requestKey()==null || event.requestKey().isBlank() ||
                event.revision()<1 || !"STAFF".equals(event.sourceType()) ||
                !"UPSERT".equals(event.eventKind()) ||
                event.payloadHash()==null ||
                !event.payloadHash().matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only standalone STAFF UPSERT events with a durable revision are supported");

        JsonNode p;
        try {
            p=json.readTree(event.payloadJson());
            if(p==null || !p.isObject()) throw new IllegalArgumentException("payload must be object");
            String canonical=json.writeValueAsString(json.convertValue(p,Object.class));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if(!MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                                      event.payloadHash().getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Assignment reference payload digest mismatch");
        } catch(ResponseStatusException ex) {
            throw ex;
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Malformed assignment reference JSON",ex);
        }

        // Strict v1 envelope: no classId and no CLASS_SUPPORT or TEACHING. This
        // deliberately leaves attendance and last-CS coverage under Core.
        String employee=text(p,"employeeId");
        String role=text(p,"staffRole");
        String workType=text(p,"workType");
        String status=text(p,"status");
        String rowId=text(p,"id");
        if(employee==null || employee.isBlank() || employee.length()>50 ||
                !event.scheduleId().equals(rowId) ||
                role==null || !Set.of("CS","SALE").contains(role) ||
                status==null || !Set.of("ASSIGNED","CANCELLED").contains(status) ||
                workType==null ||
                (p.hasNonNull("classId") && !p.get("classId").asText().isBlank()) ||
                ("CS".equals(role) && !Set.of("OPERATIONS_SUPPORT","FRONT_DESK").contains(workType)) ||
                ("SALE".equals(role) && !Set.of("SALES_SHIFT","LEAD_FOLLOWUP","CONSULTATION").contains(workType)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Class-linked and unsupported assignment reference events are forbidden");
        // A standalone cancellation is a versioned UPSERT (status=CANCELLED)
        // so the Core row and audit/reference receipt remain inspectable.
        // Never let an unrelated command issue a fake status transition.
        if(("ASSIGNED".equals(status) && !Set.of("ASSIGN_STAFF","EDIT_STAFF")
                .contains(event.operation()))
                || ("CANCELLED".equals(status) &&
                    !"CANCEL_STAFF".equals(event.operation())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Scheduling operation does not match the staff status transition");

        LocalDate date;
        LocalTime start,end;
        try {
            date=LocalDate.parse(text(p,"date"));
            start=LocalTime.parse(text(p,"startTime"));
            end=LocalTime.parse(text(p,"endTime"));
            if(!end.isAfter(start)) throw new IllegalArgumentException("Inverted shift time");
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid assignment reference shift times",ex);
        }
        String workplace=text(p,"workplaceId");
        String assignedBy=text(p,"assignedBy");
        if((workplace!=null && workplace.length()>50) ||
                assignedBy==null || assignedBy.isBlank() || assignedBy.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Assignment reference identifiers too long");

        // The Core transaction lock serializes with the operator's rollback
        // and every other Core assignment writer. Disabled until deliberate
        // maintenance-window QUIESCED mode and explicit config opt-in.
        String mode=db.queryForObject(
            "SELECT writer_mode FROM scheduling_assignment_writer_control " +
            "WHERE singleton=TRUE FOR SHARE",String.class);
        if(!"QUIESCED".equals(mode))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core reference writes require QUIESCED assignment mode");
        // A durable claim was admitted under Core QUIESCED before the
        // independent Scheduling DB transaction committed. Without this
        // claim, a local event could race the operator's CORE handback.
        List<String> claims=db.query(
            "SELECT status FROM scheduling_assignment_writer_claims " +
            "WHERE actor_username=? AND manager_employee_id=? " +
            "AND operation=? AND request_key=? FOR SHARE",
            (rs,i)->rs.getString(1),event.actorUsername(),assignedBy,
            event.operation(),event.requestKey());
        if(claims.size()!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No durable Core admission for this Scheduling command");
        String claimState=claims.get(0);

        db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))",
            "scheduling-reference:"+event.sourceType()+":"+event.scheduleId());

        List<Previous> prior=db.query(
            "SELECT last_revision,last_event_id,payload_hash FROM " +
            "scheduling_assignment_reference_receipts " +
            "WHERE source_type=? AND schedule_id=? FOR UPDATE",
            (rs,i)->new Previous(rs.getLong(1),rs.getString(2),rs.getString(3)),
            event.sourceType(),event.scheduleId());
        if(!prior.isEmpty()) {
            Previous old=prior.get(0);
            // Historical receipts are append-only. An older source event is
            // considered delivered only if Core can PROVE it was committed
            // with the exact ID, digest, actor and command identity.
            if(event.revision()<old.revision()) {
                Long exact=db.queryForObject(
                    "SELECT count(*) FROM scheduling_assignment_reference_event_receipts " +
                    "WHERE source_type=? AND schedule_id=? AND revision=? " +
                    "AND event_id=? AND payload_hash=? AND actor_username=? " +
                    "AND operation=? AND request_key=?",
                    Long.class,event.sourceType(),event.scheduleId(),
                    event.revision(),event.eventId(),event.payloadHash(),
                    event.actorUsername(),event.operation(),event.requestKey());
                if(exact!=null && exact==1)
                    return new Receipt(event.eventId(),event.revision(),"ALREADY_APPLIED");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Old reference event has no exact immutable Core proof");
            }
            if(event.revision()==old.revision()) {
                if(old.eventId().equals(event.eventId()) &&
                   old.hash().equals(event.payloadHash()))
                    return new Receipt(event.eventId(),event.revision(),"ALREADY_APPLIED");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reference revision cannot be reused for another event");
            }
            if(event.revision()!=old.revision()+1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Missing intermediate Scheduling assignment reference revision");
            // Until EDIT_STAFF has its own validated executor and reference
            // contract, the only valid next operation is cancelling the
            // Scheduling-created shift. A second ASSIGN_STAFF must not rewrite
            // an established row at a newer revision.
            if(!("CANCELLED".equals(status) && "CANCEL_STAFF".equals(event.operation()))
                    && !("ASSIGNED".equals(status) && "EDIT_STAFF".equals(event.operation())))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an edit or cancellation may follow an existing shift");
        } else {
            // Never allow a Scheduling event to overwrite an existing legacy
            // Core-owned row that has not been explicitly migrated. Creating
            // a brand-new standalone shift must start with source revision 1.
            if(event.revision()!=1 || !"ASSIGNED".equals(status) ||
                    !"ASSIGN_STAFF".equals(event.operation()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A new reference must begin with an ASSIGNED revision-1 create");
            Long legacy=db.queryForObject(
                "SELECT count(*) FROM staff_schedules WHERE staff_schedule_id=?",
                Long.class,event.scheduleId());
            if(legacy!=null && legacy>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scheduling cannot overwrite a legacy Core-owned staff assignment");
        }
        // A CONFIRMED claim may only be replayed when the last durable
        // receipt matched EXACTLY (handled by the early return above). Never
        // permit a confirmed command key to create another shift or revision.
        if(!"PENDING".equals(claimState))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scheduling writer claim already consumed by another event");

        // Metadata changes in Core may also advance its reference outbox.
        // Never lower that version just because a later Scheduling event
        // arrived with an older independent revision.
        Long existing=db.queryForObject(
            "SELECT COALESCE(max(version),0) FROM scheduling_assignment_outbox " +
            "WHERE source_type=? AND schedule_id=?",
            Long.class,event.sourceType(),event.scheduleId());
        if(existing!=null && event.revision()<=existing)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Event revision must exceed the existing Core assignment version");

        // Core is still authoritative for identity and employee status.
        // The Scheduling availability snapshot may be older than an account
        // suspension or a CM reassignment; refuse new reference commits in
        // that case instead of accepting a stale worker/assigner.
        Long activeWorker=db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.status='ACTIVE' AND u.status='ACTIVE' " +
            "AND u.role_id=?",
            Long.class,employee,role);
        // Cancellation must remain possible after an employee is disabled.
        // Prevent *new* assignments to inactive workers; the manager still
        // has to be active for either operation.
        if("ASSIGNED".equals(status) && (activeWorker==null || activeWorker!=1))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scheduling reference worker is inactive or has changed role");
        Long activeAssigner=db.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.username=? AND e.status='ACTIVE' " +
            "AND u.status='ACTIVE' AND u.role_id='CM'",
            Long.class,assignedBy,event.actorUsername());
        if(activeAssigner==null || activeAssigner!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Scheduling reference manager is inactive or unauthorized");

        // Transaction-local only. DB triggers guard this exact exceptional
        // reference path and skip Core->Scheduling feedback.
        db.queryForObject(
            "SELECT set_config('iig.assignment_reference_projection','true',true)",
            String.class);
        if("ASSIGN_STAFF".equals(event.operation())) {
            // First revision of a brand-new Scheduling-owned shift: never
            // overwrite a legacy Core-owned assignment.
            int inserted=db.update(
                "INSERT INTO staff_schedules (staff_schedule_id,employee_id," +
                "class_id,workplace_id,date,start_time,end_time,work_type,status," +
                "assigned_by,assignment_source) VALUES(?,?,NULL,?,?,?,?,?,?,?,'STANDARD')",
                event.scheduleId(),employee,workplace,date,start,end,
                workType,status,assignedBy);
            if(inserted!=1)
                throw new IllegalStateException("Core did not insert reference shift");
        } else {
            // Existing revisions must refer to a previously committed
            // standalone shift, never a class-linked or reassigned employee.
            List<ExistingShift> current=db.query(
                "SELECT employee_id,class_id,workplace_id,date,start_time," +
                "end_time,work_type,status FROM staff_schedules " +
                "WHERE staff_schedule_id=? FOR UPDATE",
                (rs,i)->new ExistingShift(rs.getString(1),rs.getString(2),
                    rs.getString(3),rs.getObject(4,LocalDate.class),
                    rs.getObject(5,LocalTime.class),rs.getObject(6,LocalTime.class),
                    rs.getString(7),rs.getString(8)),
                event.scheduleId());
            if(current.size()!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reference update requires a committed Core shift");
            ExistingShift row=current.get(0);
            if(!Objects.equals(row.employeeId(),employee) ||
                    row.classId()!=null || !"ASSIGNED".equals(row.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reference update disagrees with the existing Core shift");

            int changed;
            if("EDIT_STAFF".equals(event.operation())) {
                // The worker is immutable. Only the approved new slot and
                // work type may change; never silently rebind a class.
                changed=db.update(
                    "UPDATE staff_schedules SET workplace_id=?,date=?," +
                    "start_time=?,end_time=?,work_type=?,assigned_by=? " +
                    "WHERE staff_schedule_id=? AND employee_id=? " +
                    "AND class_id IS NULL AND status='ASSIGNED'",
                    workplace,date,start,end,workType,assignedBy,
                    event.scheduleId(),employee);
            } else {
                // Cancellation must never rewrite shift identity, dates,
                // location or work type through the follow-up event.
                if(!Objects.equals(row.workplaceId(),workplace) ||
                        !Objects.equals(row.date(),date) ||
                        !Objects.equals(row.start(),start) ||
                        !Objects.equals(row.end(),end) ||
                        !Objects.equals(row.workType(),workType))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Reference cancellation differs from committed Core shift");
                changed=db.update(
                    "UPDATE staff_schedules SET status='CANCELLED',assigned_by=? " +
                    "WHERE staff_schedule_id=? AND status='ASSIGNED'",
                    assignedBy,event.scheduleId());
            }
            if(changed!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Concurrent Core reference update or cancellation");
        }
        db.update("INSERT INTO scheduling_assignment_reference_receipts " +
            "(source_type,schedule_id,last_revision,last_event_id,payload_hash,actor_username) " +
            "VALUES(?,?,?,?,?,?) ON CONFLICT(source_type,schedule_id) " +
            "DO UPDATE SET last_revision=EXCLUDED.last_revision," +
            "last_event_id=EXCLUDED.last_event_id,payload_hash=EXCLUDED.payload_hash," +
            "actor_username=EXCLUDED.actor_username,applied_at=NOW()",
            event.sourceType(),event.scheduleId(),event.revision(),
            event.eventId(),event.payloadHash(),event.actorUsername());
        // Keep every acknowledged revision, not just the last snapshot.
        // The table rejects mutation and duplicate source/revision events.
        // If any insert fails, the entire Core projection rolls back.
        db.update(
            "INSERT INTO scheduling_assignment_reference_event_receipts " +
            "(event_id,source_type,schedule_id,revision,payload_hash," +
            "actor_username,operation,request_key) VALUES(?,?,?,?,?,?,?,?)",
            event.eventId(),event.sourceType(),event.scheduleId(),
            event.revision(),event.payloadHash(),event.actorUsername(),
            event.operation(),event.requestKey());
        db.update("INSERT INTO scheduling_assignment_outbox " +
            "(source_type,schedule_id,version,delivered_at) " +
            "VALUES(?,?,?,NOW()) ON CONFLICT(source_type,schedule_id) " +
            "DO UPDATE SET version=EXCLUDED.version,delivered_at=NOW(),updated_at=NOW()",
            event.sourceType(),event.scheduleId(),event.revision());
        // Confirm the Core-side claim in the SAME transaction as the
        // assignment reference row and durable receipt. Retry can then
        // safely return ALREADY_APPLIED without acknowledging a new event.
        int claimed=db.update(
            "UPDATE scheduling_assignment_writer_claims " +
            "SET status='CONFIRMED',confirmed_at=NOW() " +
            "WHERE actor_username=? AND manager_employee_id=? " +
            "AND operation=? AND request_key=? AND status='PENDING'",
            event.actorUsername(),assignedBy,event.operation(),event.requestKey());
        if(claimed!=1)
            throw new IllegalStateException(
                "Could not confirm Core writer claim with reference transaction");
        return new Receipt(event.eventId(),event.revision(),"APPLIED");
    }

    private String text(JsonNode node,String key) {
        return node.hasNonNull(key)?node.get(key).asText():null;
    }
}

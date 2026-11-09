package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
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
                !Set.of("CS","SALE").contains(role) ||
                !Set.of("ASSIGNED","CANCELLED").contains(status) ||
                (p.hasNonNull("classId") && !p.get("classId").asText().isBlank()) ||
                ("CS".equals(role) && !Set.of("OPERATIONS_SUPPORT","FRONT_DESK").contains(workType)) ||
                ("SALE".equals(role) && !Set.of("SALES_SHIFT","LEAD_FOLLOWUP","CONSULTATION").contains(workType)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Class-linked and unsupported assignment reference events are forbidden");
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
                (assignedBy!=null && assignedBy.length()>50))
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
            if(event.revision()<old.revision())
                return new Receipt(event.eventId(),event.revision(),"SUPERSEDED");
            if(event.revision()==old.revision()) {
                if(old.eventId().equals(event.eventId()) &&
                   old.hash().equals(event.payloadHash()))
                    return new Receipt(event.eventId(),event.revision(),"ALREADY_APPLIED");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Reference revision cannot be reused for another event");
            }
        } else {
            Long existing=db.queryForObject(
                "SELECT COALESCE(max(version),0) FROM scheduling_assignment_outbox " +
                "WHERE source_type=? AND schedule_id=?",
                Long.class,event.sourceType(),event.scheduleId());
            if(existing!=null && event.revision()<=existing)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event revision must exceed the existing Core assignment version");
        }

        // Transaction-local only. DB triggers guard this exact exceptional
        // reference path and skip Core->Scheduling feedback.
        db.queryForObject(
            "SELECT set_config('iig.assignment_reference_projection','true',true)",
            String.class);
        db.update("INSERT INTO staff_schedules (staff_schedule_id,employee_id," +
            "class_id,workplace_id,date,start_time,end_time,work_type,status," +
            "assigned_by,assignment_source) VALUES(?,?,NULL,?,?,?,?,?,?,?,'SCHEDULING') " +
            "ON CONFLICT(staff_schedule_id) DO UPDATE SET " +
            "employee_id=EXCLUDED.employee_id,class_id=NULL," +
            "workplace_id=EXCLUDED.workplace_id,date=EXCLUDED.date," +
            "start_time=EXCLUDED.start_time,end_time=EXCLUDED.end_time," +
            "work_type=EXCLUDED.work_type,status=EXCLUDED.status," +
            "assigned_by=EXCLUDED.assigned_by,assignment_source='SCHEDULING'",
            event.scheduleId(),employee,workplace,date,start,end,workType,status,assignedBy);
        db.update("INSERT INTO scheduling_assignment_reference_receipts " +
            "(source_type,schedule_id,last_revision,last_event_id,payload_hash,actor_username) " +
            "VALUES(?,?,?,?,?,?) ON CONFLICT(source_type,schedule_id) " +
            "DO UPDATE SET last_revision=EXCLUDED.last_revision," +
            "last_event_id=EXCLUDED.last_event_id,payload_hash=EXCLUDED.payload_hash," +
            "actor_username=EXCLUDED.actor_username,applied_at=NOW()",
            event.sourceType(),event.scheduleId(),event.revision(),
            event.eventId(),event.payloadHash(),event.actorUsername());
        db.update("INSERT INTO scheduling_assignment_outbox " +
            "(source_type,schedule_id,version,delivered_at) " +
            "VALUES(?,?,?,NOW()) ON CONFLICT(source_type,schedule_id) " +
            "DO UPDATE SET version=EXCLUDED.version,delivered_at=NOW(),updated_at=NOW()",
            event.sourceType(),event.scheduleId(),event.revision());
        return new Receipt(event.eventId(),event.revision(),"APPLIED");
    }

    private String text(JsonNode node,String key) {
        return node.hasNonNull(key)?node.get(key).asText():null;
    }
}

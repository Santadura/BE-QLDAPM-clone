package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent Attendance transaction boundary, deliberately NOT connected
 * to a Gateway mutation yet. A future native-writer admission must prove
 * exclusive Core handoff before this database is allowed to accept commands.
 *
 * The core-reference transaction is a different PostgreSQL commit. Pending
 * reference is NOT successful attendance; immutable event/receipt evidence
 * must be reconciled before a client is told the business write completed.
 */
@Repository
public class AttendanceNativeLedgerRepository {
    public record Admission(String actorUsername,String actorEmployeeId,
        String classId,String teachingScheduleId,String requestKey,
        String requestHash,String status,String factsToken,
        long writerGeneration,String coreClaimId,String previousAttendanceId,
        String previousStatus,String ownerMode) {}
    public record Pending(String eventId,String actorUsername,String classId,
        String teachingScheduleId,String requestKey,long version,
        String payloadCanonical,String payloadHash) {}
    public record Committed(String eventId,String status,long version,
        boolean referenceConfirmed) {}
    public record Activation(String adminUsername,long writerGeneration,
        long sourceRows,long historicalEvents,long sourceLastEventId,
        String academicProofHash) {}
    private record Previous(String id,long version,String status) {}
    private record Command(String eventId,String digest,String factsToken,
        long generation,String state) {}
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AttendanceNativeLedgerRepository(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db;this.mapper=mapper;
    }

    static String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required",ex);
        }
    }

    private static boolean safe(String s,int max) {
        return s!=null && s.length()<=max &&
            s.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }

    private static void validate(Admission a) {
        if(a==null || !safe(a.actorUsername(),100) ||
            !safe(a.actorEmployeeId(),50) || !safe(a.classId(),50) ||
            !safe(a.teachingScheduleId(),50) || !safe(a.requestKey(),128) ||
            !safe(a.coreClaimId(),50) ||
            !Set.of("PRESENT","ABSENT","LATE").contains(a.status()) ||
            a.factsToken()==null || !a.factsToken().matches("[a-f0-9]{64}") ||
            a.requestHash()==null || !a.requestHash().matches("[a-f0-9]{64}") ||
            !AttendanceCommandService.fingerprint(
                a.classId(),a.teachingScheduleId(),a.status()).equals(a.requestHash()) ||
            a.writerGeneration()<1 ||
            (a.previousAttendanceId()==null)!=(a.previousStatus()==null) ||
            (a.previousAttendanceId()!=null &&
                !safe(a.previousAttendanceId(),50)) ||
            (a.previousStatus()!=null &&
                !Set.of("PRESENT","ABSENT","LATE").contains(a.previousStatus())) ||
            !"ATTENDANCE".equals(a.ownerMode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Exact Core-admitted native Attendance writer identity required");
    }

    /**
     * Install exactly one immutable ownership snapshot while the old Core
     * writer and all assignment/Academic writers are frozen and the complete
     * cross-service immutable history has been independently verified.
     * This method NEVER switches the Core writer itself.
     */
    @Transactional
    public long activate(Activation proof) {
        if(proof==null || !safe(proof.adminUsername(),100) ||
            proof.writerGeneration()<2 || proof.sourceRows()<0 ||
            proof.historicalEvents()<0 || proof.sourceLastEventId()<0 ||
            proof.academicProofHash()==null ||
            !proof.academicProofHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact frozen Attendance/Academic activation evidence required");
        Long existing=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_native_activations",Long.class);
        Long commands=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_native_commands",Long.class);
        Long nativeRows=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_native_teacher_rows",Long.class);
        Long sourceRows=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_ledger",Long.class);
        Long events=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_event_history",Long.class);
        Long cursor=db.queryForObject(
            "SELECT last_event_id FROM attendance_history_backfill_cursor "+
            "WHERE singleton=TRUE",Long.class);
        if(!Long.valueOf(0).equals(existing) ||
            !Long.valueOf(0).equals(commands) ||
            !Long.valueOf(0).equals(nativeRows) ||
            !Long.valueOf(proof.sourceRows()).equals(sourceRows) ||
            !Long.valueOf(proof.historicalEvents()).equals(events) ||
            !Long.valueOf(proof.sourceLastEventId()).equals(cursor))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance baseline is incomplete or ownership already activated");
        Long marked=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_teacher_ledger "+
            "WHERE event_kind='UPSERT'",Long.class);
        if(marked==null || marked<0 || marked>sourceRows)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Invalid frozen native Attendance source count");
        // Source receipts include full ORIGINAL timestamps. No reconstructed
        // amount/teacher/attendance status may be silently invented.
        int inserted=db.update(
            "INSERT INTO attendance_native_teacher_rows "+
            "(teaching_schedule_id,attendance_id,status,recorded_by,version,"+
            "recorded_at,updated_at) "+
            "SELECT teaching_schedule_id,attendance_id,status,recorded_by,0,"+
            "(source_payload->>'recorded_at')::timestamptz,"+
            "(source_payload->>'updated_at')::timestamptz "+
            "FROM attendance_teacher_ledger WHERE event_kind='UPSERT' "+
            "ORDER BY teaching_schedule_id");
        if(inserted!=marked)
            throw new IllegalStateException("Native Attendance baseline import torn");
        int activated=db.update(
            "INSERT INTO attendance_native_activations "+
            "(writer_generation,source_rows,historical_events,source_last_event_id,"+
            "academic_proof_hash,activated_by,imported_live_rows) "+
            "VALUES(?,?,?,?,?,?,?)",
            proof.writerGeneration(),proof.sourceRows(),proof.historicalEvents(),
            proof.sourceLastEventId(),proof.academicProofHash(),
            proof.adminUsername(),marked);
        if(activated!=1)
            throw new IllegalStateException("Cannot commit native ownership activation");
        return marked;
    }

    /**
     * CALL ONLY after a Core admission was durably recorded under an
     * ATTENDANCE-only writer lock. No cross-DB transaction is pretended.
     * This local command+domain+outbox commit is atomic and idempotent.
     */
    @Transactional
    public Committed stage(Admission a) {
        validate(a);
        Long active=db.queryForObject(
            "SELECT COUNT(*) FROM attendance_native_activations "+
            "WHERE writer_generation=?",Long.class,a.writerGeneration());
        if(!Long.valueOf(1).equals(active))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance owner has no immutable frozen activation");
        // A never-marked teacher schedule has no Core attendance row to
        // import. Only an exact Core claim proving an ABSENT baseline may
        // create an empty native record with a stable generated ID.
        if(a.previousAttendanceId()==null)
            db.update(
                "INSERT INTO attendance_native_teacher_rows "+
                "(teaching_schedule_id,attendance_id,status,recorded_by,"+
                "version,recorded_at,updated_at) "+
                "VALUES(?,?,NULL,NULL,0,NULL,NOW()) "+
                "ON CONFLICT(teaching_schedule_id) DO NOTHING",
                a.teachingScheduleId(),UUID.randomUUID().toString());
        List<Previous> nativeRows=db.query(
            "SELECT attendance_id,version,status "+
            "FROM attendance_native_teacher_rows "+
            "WHERE teaching_schedule_id=? FOR UPDATE",
            (rs,i)->new Previous(rs.getString(1),rs.getLong(2),
                rs.getString(3)),a.teachingScheduleId());
        if(nativeRows.size()!=1 ||
            (a.previousAttendanceId()!=null &&
                !a.previousAttendanceId().equals(nativeRows.get(0).id())) ||
            !java.util.Objects.equals(
                a.previousStatus(),nativeRows.get(0).status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Attendance baseline does not match Core's claim");

        String random=UUID.randomUUID().toString();
        int inserted=db.update(
            "INSERT INTO attendance_native_commands "+
            "(actor_username,class_id,teaching_schedule_id,request_key,"+
            "request_hash,facts_token,writer_generation,event_id) "+
            "VALUES(?,?,?,?,?,?,?,CAST(? AS UUID)) "+
            "ON CONFLICT(actor_username,class_id,teaching_schedule_id,request_key) "+
            "DO NOTHING",
            a.actorUsername(),a.classId(),a.teachingScheduleId(),a.requestKey(),
            a.requestHash(),a.factsToken(),a.writerGeneration(),random);

        List<Command> current=db.query(
            "SELECT event_id::text,request_hash,facts_token,writer_generation,state "+
            "FROM attendance_native_commands WHERE actor_username=? AND class_id=? "+
            "AND teaching_schedule_id=? AND request_key=? FOR UPDATE",
            (rs,i)->new Command(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getLong(4),rs.getString(5)),
            a.actorUsername(),a.classId(),a.teachingScheduleId(),a.requestKey());
        if(current.size()!=1 || !a.requestHash().equals(current.get(0).digest()) ||
            !a.factsToken().equals(current.get(0).factsToken()) ||
            a.writerGeneration()!=current.get(0).generation())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native attendance command ID reused with different evidence");

        Command command=current.get(0);
        if(inserted==0) {
            var existing=event(command.eventId());
            if(existing==null || !existing.actorUsername().equals(a.actorUsername()) ||
                !existing.requestKey().equals(a.requestKey()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Partial Attendance command/outbox transaction");
            return new Committed(existing.eventId(),a.status(),existing.version(),
                "COMMITTED".equals(command.state()));
        }

        Previous before=nativeRows.get(0);
        long version=before.version()+1;
        if(version<=0)throw new IllegalStateException("Attendance version overflow");
        Instant now=Instant.now();
        int updated=db.update(
            "UPDATE attendance_native_teacher_rows SET version=?,status=?,"+
            "recorded_by=?,recorded_at=COALESCE(recorded_at,?),"+
            "updated_at=? WHERE teaching_schedule_id=? AND version=?",
            version,a.status(),a.actorEmployeeId(),
            java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),
            a.teachingScheduleId(),before.version());
        if(updated!=1)
            throw new IllegalStateException("Concurrent Attendance native ledger version");

        ObjectNode body=mapper.createObjectNode();
        body.put("eventId",command.eventId());
        body.put("actorUsername",a.actorUsername());
        body.put("recordedBy",a.actorEmployeeId());
        body.put("classId",a.classId());
        body.put("teachingScheduleId",a.teachingScheduleId());
        body.put("requestKey",a.requestKey());
        body.put("requestHash",a.requestHash());
        body.put("factsToken",a.factsToken());
        body.put("writerGeneration",a.writerGeneration());
        body.put("coreClaimId",a.coreClaimId());
        body.put("attendanceId",before.id());
        if(a.previousAttendanceId()==null)body.putNull("previousAttendanceId");
        else body.put("previousAttendanceId",a.previousAttendanceId());
        if(a.previousStatus()==null)body.putNull("previousStatus");
        else body.put("previousStatus",a.previousStatus());
        body.put("status",a.status());
        body.put("version",version);
        body.put("updatedAt",now.toString());
        String canonical=body.toString();
        int created=db.update(
            "INSERT INTO attendance_native_outbox "+
            "(event_id,actor_username,class_id,teaching_schedule_id,request_key,"+
            "version,payload,payload_canonical,payload_hash) "+
            "VALUES(CAST(? AS UUID),?,?,?,?,?,CAST(? AS JSONB),?,?)",
            command.eventId(),a.actorUsername(),a.classId(),
            a.teachingScheduleId(),a.requestKey(),version,canonical,
            canonical,digest(canonical));
        if(created!=1)
            throw new IllegalStateException("Native Attendance immutable outbox did not commit");
        return new Committed(command.eventId(),a.status(),version,false);
    }

    /**
     * A missing Core reference or a transport timeout MUST retain the event.
     * This method is read-only and never guesses whether the Core committed.
     */
    public Pending event(String eventId) {
        if(!safe(eventId,50))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid immutable Attendance event ID required");
        List<Pending> found=db.query(
            "SELECT event_id::text,actor_username,class_id,teaching_schedule_id,"+
            "request_key,version,payload_canonical,payload_hash "+
            "FROM attendance_native_outbox WHERE event_id=CAST(? AS UUID)",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),
                rs.getString(7),rs.getString(8)),eventId);
        if(found.size()>1)throw new IllegalStateException("Duplicate native outbox ID");
        return found.isEmpty()?null:found.get(0);
    }

    public List<Pending> pending(int limit) {
        if(limit<1 || limit>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Attendance relay batch must be 1..50");
        return db.query(
            "SELECT event_id::text,actor_username,class_id,teaching_schedule_id,"+
            "request_key,version,payload_canonical,payload_hash "+
            "FROM attendance_native_outbox WHERE confirmed_at IS NULL "+
            "ORDER BY created_at,event_id LIMIT ?",
            (rs,i)->new Pending(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),
                rs.getString(7),rs.getString(8)),limit);
    }

    /**
     * An exact Core receipt is the ONLY authority for local finality.
     * A transactionally recorded receipt and command state must agree.
     */
    @Transactional
    public void confirm(Pending event,JsonNode coreReceipt) {
        if(event==null || !safe(event.eventId(),50) ||
            event.payloadCanonical()==null || event.payloadHash()==null ||
            !digest(event.payloadCanonical()).equals(event.payloadHash()) ||
            coreReceipt==null || !coreReceipt.isObject() ||
            !event.eventId().equals(coreReceipt.path("eventId").asText()) ||
            event.version()!=coreReceipt.path("version").asLong(-1) ||
            !event.payloadHash().equals(coreReceipt.path("payloadHash").asText()) ||
            !event.teachingScheduleId().equals(
                coreReceipt.path("teachingScheduleId").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core receipt does not confirm exact native Attendance event");
        int ack=db.update(
            "UPDATE attendance_native_outbox "+
            "SET confirmed_at=NOW(),reference_receipt=CAST(? AS JSONB) "+
            "WHERE event_id=CAST(? AS UUID) AND payload_hash=? "+
            "AND version=? AND confirmed_at IS NULL",
            coreReceipt.toString(),event.eventId(),event.payloadHash(),event.version());
        if(ack==0) {
            var receipts=db.query(
                "SELECT reference_receipt::text FROM attendance_native_outbox "+
                "WHERE event_id=CAST(? AS UUID) AND payload_hash=? AND confirmed_at IS NOT NULL",
                (rs,i)->rs.getString(1),event.eventId(),event.payloadHash());
            try {
                if(receipts.size()!=1 ||
                    !coreReceipt.equals(mapper.readTree(receipts.get(0))))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Core Attendance receipt replay diverged");
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Corrupted native Attendance reference receipt",ex);
            }
        } else if(ack!=1) {
            throw new IllegalStateException("Native attendance acknowledgement ambiguous");
        }
        int finished=db.update(
            "UPDATE attendance_native_commands SET state='COMMITTED',"+
            "completed_at=NOW() WHERE event_id=CAST(? AS UUID) "+
            "AND state='PENDING_REFERENCE'",
            event.eventId());
        if(finished>1)
            throw new IllegalStateException("Ambiguous native attendance command completion");
    }
}

package vn.iig.academic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A real, independent Academic PostgreSQL mutation/receipt/outbox boundary.
 * It is deliberately REHEARSAL_ONLY, not a production ownership handoff.
 * Every command's row changes + original response + immutable outbox are
 * ONE local transaction. No Core DB connection, Core mutation or event sender.
 *
 * Conservative scope: originally imported Core lessons are immutable here.
 * Only native-origin lessons can be cancelled/removed until Scheduling,
 * Attendance, student clash and Payroll reservation contracts are authoritative.
 */
@Repository
public class AcademicNativeSessionRepository {
    public record Activation(String classId,String classStatus,
        LocalDate startDate,LocalDate endDate,long sessionGeneration,
        long rosterGeneration,long lastSourceEventId,String actor) {}
    private record Gate(String status,LocalDate start,LocalDate end,
        long generation,long rosterGeneration) {}
    private record Slot(String id,LocalDate date,LocalTime start,
        LocalTime end,String status,boolean sourceImported,long version) {}
    private record Prior(String digest,String response) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private static final ZoneId TIMEZONE=ZoneId.of("Asia/Ho_Chi_Minh");

    public AcademicNativeSessionRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;
        this.json=json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    }
    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }
    private static boolean safe(String value,int max) {
        return value!=null && value.length()<=max &&
            value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*");
    }
    private static void requireIdentity(String actor,String classId,String key) {
        if(!safe(actor,100)||!safe(classId,50)||!safe(key,128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Exact Academic actor/class/idempotency key required");
    }
    private String fingerprint(String operation,String classId,JsonNode body) {
        try {
            String canonical=json.writeValueAsString(json.convertValue(body,Object.class));
            return AcademicSessionSourceRepository.sha256(
                "academic-native-rehearsal-v1\n"+operation+"\n"+classId+"\n"+canonical);
        } catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Academic native command payload",ex);
        }
    }

    /**
     * Initialize only from frozen, verified Core->Academic source parity.
     * A caller must first prove the complete immutable history and currently
     * valid Admin/session/roster writer generations; no independent API may
     * accept a self-asserted activation request from a public client.
     */
    @Transactional
    public long activate(Activation a) {
        if(a==null || !safe(a.actor(),100)||!safe(a.classId(),50) ||
            !Set.of("READY","RUNNING").contains(a.classStatus()) ||
            a.startDate()==null || a.endDate()==null ||
            a.endDate().isBefore(a.startDate()) ||
            a.sessionGeneration()<1 || a.rosterGeneration()<1 ||
            a.lastSourceEventId()<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete frozen native timetable bootstrap evidence required");
        Long activated=db.queryForObject(
            "SELECT COUNT(*) FROM academic_native_session_activations WHERE class_id=?",
            Long.class,a.classId());
        if(!Long.valueOf(0L).equals(activated))
            throw conflict("Independent Academic class has already been bootstrapped");
        Long expected=db.queryForObject(
            "SELECT COUNT(*) FROM academic_class_sessions_shadow WHERE class_id=? "+
            "AND event_kind='UPSERT'",Long.class,a.classId());
        Long total=db.queryForObject(
            "SELECT COUNT(*) FROM academic_native_session_rows WHERE class_id=?",
            Long.class,a.classId());
        if(expected==null || total==null || total!=0)
            throw conflict("Academic native source baseline incomplete or already modified");

        int inserted=db.update(
            "INSERT INTO academic_native_session_activations "+
            "(class_id,writer_generation,roster_generation,source_last_event_id,"+
            "class_status,start_date,end_date,activated_by) VALUES(?,?,?,?,?,?,?,?)",
            a.classId(),a.sessionGeneration(),a.rosterGeneration(),
            a.lastSourceEventId(),a.classStatus(),a.startDate(),
            a.endDate(),a.actor());
        if(inserted!=1)throw new IllegalStateException("Native activation insert failed");
        int copied=db.update(
            "INSERT INTO academic_native_session_rows "+
            "(class_session_id,class_id,session_date,start_time,end_time,status,"+
            "source_imported,version) "+
            "SELECT class_session_id,class_id,session_date,start_time,end_time,status,"+
            "TRUE,0 FROM academic_class_sessions_shadow "+
            "WHERE class_id=? AND event_kind='UPSERT' ORDER BY class_session_id",
            a.classId());
        if(copied!=expected.intValue())
            throw new IllegalStateException("Academic native baseline copy torn");
        return copied;
    }

    private Gate lockedActivation(String classId,long generation,long rosterGeneration) {
        var rows=db.query(
            "SELECT class_status,start_date,end_date,writer_generation,roster_generation "+
            "FROM academic_native_session_activations "+
            "WHERE class_id=? AND state='REHEARSAL_ONLY' FOR UPDATE",
            (rs,i)->new Gate(rs.getString(1),rs.getObject(2,LocalDate.class),
                rs.getObject(3,LocalDate.class),rs.getLong(4),rs.getLong(5)),classId);
        if(rows.size()!=1 || rows.get(0).generation()!=generation ||
            rows.get(0).rosterGeneration()!=rosterGeneration)
            throw conflict("Academic native writer has no matching frozen activation");
        return rows.get(0);
    }
    private List<Slot> lockedSlots(String classId) {
        return db.query(
            "SELECT class_session_id,session_date,start_time,end_time,status,"+
            "source_imported,version FROM academic_native_session_rows "+
            "WHERE class_id=? ORDER BY session_date,start_time,class_session_id FOR UPDATE",
            (rs,i)->new Slot(rs.getString(1),rs.getObject(2,LocalDate.class),
                rs.getObject(3,LocalTime.class),rs.getObject(4,LocalTime.class),
                rs.getString(5),rs.getBoolean(6),rs.getLong(7)),classId);
    }
    private static LocalDate date(JsonNode value,String name) {
        if(value==null || !value.isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native "+name+" date missing");
        try{return LocalDate.parse(value.asText());}
        catch(DateTimeParseException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Academic native "+name+" date",ex);
        }
    }
    private static LocalTime time(JsonNode value,String name) {
        if(value==null || !value.isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native "+name+" time missing");
        try{
            LocalTime t=LocalTime.parse(value.asText());
            if(t.getNano()!=0 || t.getSecond()!=0)throw new DateTimeParseException(
                "Minute precision required",value.asText(),0);
            return t;
        } catch(DateTimeParseException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Academic native "+name+" time",ex);
        }
    }
    private static void validInterval(Gate gate,LocalDate day,
            LocalTime start,LocalTime end) {
        if(day.isBefore(gate.start()) || day.isAfter(gate.end()) ||
            !end.isAfter(start))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native lesson outside class range or invalid time");
    }
    private static boolean overlaps(Slot slot,LocalDate day,LocalTime start,LocalTime end) {
        return "PLANNED".equals(slot.status()) && slot.date().equals(day) &&
            slot.start().isBefore(end) && slot.end().isAfter(start);
    }
    private static boolean same(Slot row,LocalDate day,LocalTime start,LocalTime end) {
        return row.date().equals(day) && row.start().equals(start) && row.end().equals(end);
    }
    private static String requiredText(JsonNode body,String field) {
        JsonNode node=body.path(field);
        if(!node.isTextual() || node.asText().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native "+field+" is required");
        return node.asText();
    }
    private static boolean future(Slot slot) {
        return LocalDateTime.of(slot.date(),slot.start()).isAfter(
            LocalDateTime.now(TIMEZONE));
    }
    private List<Slot> plan(String classId,Gate gate,List<Slot> current,JsonNode payload) {
        if(!payload.isArray() || payload.size()>400)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native plan must contain <=400 lessons");
        List<Slot> expected=new ArrayList<>();
        for(JsonNode item:payload) {
            if(item==null || !item.isObject())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Academic native plan item must be a lesson");
            LocalDate day=date(item.get("date"),"lesson");
            LocalTime start=time(item.get("startTime"),"start");
            LocalTime end=time(item.get("endTime"),"end");
            validInterval(gate,day,start,end);
            for(Slot existing:expected)
                if(overlaps(existing,day,start,end))
                    throw conflict("Academic native plan overlaps another lesson");
            expected.add(new Slot(null,day,start,end,"PLANNED",false,1));
        }
        for(Slot prior:current) {
            if(!"PLANNED".equals(prior.status()))continue;
            boolean keep=expected.stream().anyMatch(s->same(prior,s.date(),s.start(),s.end()));
            if(!keep && (prior.sourceImported() || "RUNNING".equals(gate.status()) || !future(prior)))
                throw conflict("Core-imported, running or past lessons cannot be removed in native rehearsal");
        }
        List<Slot> result=new ArrayList<>(current);
        for(Slot prior:current) {
            if(!"PLANNED".equals(prior.status()) || prior.sourceImported())continue;
            boolean keep=expected.stream().anyMatch(s->same(prior,s.date(),s.start(),s.end()));
            if(!keep) {
                int count=db.update(
                    "UPDATE academic_native_session_rows SET status='CANCELLED',"+
                    "version=version+1,updated_at=NOW() "+
                    "WHERE class_session_id=? AND version=? AND status='PLANNED'",
                    prior.id(),prior.version());
                if(count!=1)throw new IllegalStateException("Native session revision race");
                result.remove(prior);
                result.add(new Slot(prior.id(),prior.date(),prior.start(),prior.end(),
                    "CANCELLED",false,prior.version()+1));
            }
        }
        for(Slot candidate:expected) {
            boolean present=false;
            for(Slot old:result) {
                if(same(old,candidate.date(),candidate.start(),candidate.end())) {
                    if(!"PLANNED".equals(old.status()))
                        throw conflict("A cancelled native lesson cannot be reactivated");
                    present=true;
                } else if(overlaps(old,candidate.date(),candidate.start(),candidate.end())) {
                    throw conflict("Academic native plan overlaps existing timetable");
                }
            }
            if(!present) {
                if("RUNNING".equals(gate.status()) &&
                    !LocalDateTime.of(candidate.date(),candidate.start())
                    .isAfter(LocalDateTime.now(TIMEZONE)))
                    throw conflict("Running class cannot acquire a past lesson");
                Slot created=new Slot(UUID.randomUUID().toString(),candidate.date(),
                    candidate.start(),candidate.end(),"PLANNED",false,1);
                insert(classId,created);
                result.add(created);
            }
        }
        return result;
    }
    private List<Slot> reschedule(String classId,Gate gate,
            List<Slot> current,JsonNode payload) {
        if(!payload.isObject() || !"RUNNING".equals(gate.status()))
            throw conflict("Native reschedule requires a Running class");
        String original=requiredText(payload,"originalSessionId");
        String reason=requiredText(payload,"reason");
        if(reason.trim().length()<5)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Academic reschedule needs a reason of 5+ characters");
        Slot old=current.stream().filter(s->s.id().equals(original) &&
            "PLANNED".equals(s.status())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Original Academic lesson not found"));
        // Imported lessons can have teacher/staff assignments or Payroll
        // references in Core. Do not cancel them without independent saga proof.
        if(old.sourceImported() || !future(old))
            throw conflict("Only future native-origin, unassigned lessons may be rescheduled");
        boolean makeup=payload.hasNonNull("newDate");
        if(makeup!=(payload.hasNonNull("newStartTime") &&
                   payload.hasNonNull("newEndTime")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Complete replacement date/start/end or all-null cancellation required");
        Slot replacement=null;
        if(makeup) {
            LocalDate day=date(payload.get("newDate"),"replacement");
            LocalTime start=time(payload.get("newStartTime"),"new start");
            LocalTime end=time(payload.get("newEndTime"),"new end");
            validInterval(gate,day,start,end);
            if(!LocalDateTime.of(day,start).isAfter(LocalDateTime.now(TIMEZONE)))
                throw conflict("Make-up lesson must be in the future");
            for(Slot other:current) {
                if(other.id().equals(old.id()))continue;
                if(same(other,day,start,end) || overlaps(other,day,start,end))
                    throw conflict("Make-up lesson conflicts with an existing slot");
            }
            replacement=new Slot(UUID.randomUUID().toString(),day,start,end,
                "PLANNED",false,1);
        }
        int changed=db.update(
            "UPDATE academic_native_session_rows SET status='CANCELLED',"+
            "version=version+1,updated_at=NOW() "+
            "WHERE class_session_id=? AND version=? AND status='PLANNED' "+
            "AND source_imported=FALSE",old.id(),old.version());
        if(changed!=1)throw new IllegalStateException("Native reschedule conflict");
        List<Slot> result=new ArrayList<>(current);
        result.remove(old);
        result.add(new Slot(old.id(),old.date(),old.start(),old.end(),
            "CANCELLED",false,old.version()+1));
        if(replacement!=null) {insert(classId,replacement);result.add(replacement);}
        return result;
    }
    private void insert(String classId,Slot s) {
        int n=db.update(
            "INSERT INTO academic_native_session_rows "+
            "(class_session_id,class_id,session_date,start_time,end_time,status,"+
            "source_imported,version) VALUES(?,?,?,?,?,'PLANNED',FALSE,1)",
            s.id(),classId,s.date(),s.start(),s.end());
        if(n!=1)throw new IllegalStateException("Native session insert failed");
    }
    private ArrayNode response(String classId,List<Slot> slots) {
        ArrayNode list=json.createArrayNode();
        slots.stream().sorted(Comparator.comparing(Slot::date)
            .thenComparing(Slot::start).thenComparing(Slot::id))
            .forEach(s->list.add(json.createObjectNode()
                .put("id",s.id()).put("classId",classId)
                .put("date",s.date().toString())
                .put("startTime",s.start().toString())
                .put("endTime",s.end().toString())
                .put("status",s.status())));
        return list;
    }

    /** Atomic domain rows, replay receipt and undeliverable rehearsal outbox. */
    @Transactional
    public JsonNode execute(String actor,String classId,String operation,
            String key,JsonNode body,long sessionGeneration,long rosterGeneration) {
        requireIdentity(actor,classId,key);
        if(!Set.of("PLAN","RESCHEDULE").contains(operation) || body==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Native Academic operation and payload required");
        String digest=fingerprint(operation,classId,body);
        Gate gate=lockedActivation(classId,sessionGeneration,rosterGeneration);
        List<Prior> prior=db.query(
            "SELECT request_hash,response_json::text FROM academic_native_session_commands "+
            "WHERE actor_username=? AND class_id=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->new Prior(rs.getString(1),rs.getString(2)),
            actor,classId,operation,key);
        if(prior.size()>1)throw new IllegalStateException("Duplicate native command");
        if(!prior.isEmpty()) {
            if(!digest.equals(prior.get(0).digest()))
                throw conflict("Native idempotency key reused for changed request");
            try{return json.readTree(prior.get(0).response());}
            catch(JsonProcessingException ex){throw new IllegalStateException(
                "Original native Academic command result corrupted",ex);}
        }
        List<Slot> before=lockedSlots(classId);
        List<Slot> after="PLAN".equals(operation)
            ?plan(classId,gate,before,body)
            :reschedule(classId,gate,before,body);
        ArrayNode original=response(classId,before);
        ArrayNode result=response(classId,after);
        int count=db.update(
            "INSERT INTO academic_native_session_commands "+
            "(actor_username,class_id,operation,request_key,request_hash,"+
            "response_json,writer_generation) VALUES(?,?,?,?,?,CAST(? AS JSONB),?)",
            actor,classId,operation,key,digest,result.toString(),sessionGeneration);
        if(count!=1)throw new IllegalStateException("Native command receipt not committed");
        ObjectNode event=json.createObjectNode()
            .put("eventId",UUID.randomUUID().toString())
            .put("actor",actor).put("classId",classId)
            .put("operation",operation).put("requestKey",key)
            .put("requestHash",digest)
            .put("writerGeneration",sessionGeneration)
            .set("after",result);
        event.set("before",original);
        // Store the EXACT bytes that are hashed; JSONB pretty-print or
        // field reordering cannot silently mutate the historical digest.
        String canonical=event.toString();
        int emitted=db.update(
            "INSERT INTO academic_native_session_outbox "+
            "(event_id,actor_username,class_id,operation,request_key,"+
            "writer_generation,payload,payload_canonical,payload_hash) "+
            "VALUES(CAST(? AS UUID),?,?,?,?,?,CAST(? AS JSONB),?,?)",
            event.path("eventId").asText(),actor,classId,operation,key,
            sessionGeneration,canonical,canonical,
            AcademicSessionSourceRepository.sha256(canonical));
        if(emitted!=1)throw new IllegalStateException(
            "Native timetable mutation has no immutable outbox");
        return result;
    }
}

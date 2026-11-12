package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A single independent Academic PostgreSQL transaction commits an immutable
 * Core event receipt, a typed latest STUDENT/MEMBERSHIP shadow, and cursor.
 * DELETE tombstones remain visible to reconciliation, not to public reads.
 */
@Repository
public class AcademicRosterSourceRepository {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AcademicRosterSourceRepository(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db;this.mapper=mapper;
    }
    private static ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,reason);
    }

    public long cursor() {
        Long value=db.queryForObject(
            "SELECT last_event_id FROM academic_roster_history_cursor WHERE singleton=TRUE",
            Long.class);
        if(value==null || value<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic roster history cursor unavailable");
        return value;
    }
    public long receiptCount() {
        Long count=db.queryForObject("SELECT COUNT(*) FROM academic_roster_source_receipts",
            Long.class);
        if(count==null || count<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic roster receipts unavailable");
        return count;
    }
    public long studentCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM academic_students_shadow WHERE event_kind='UPSERT'",
            Long.class);
        if(count==null || count<0)throw rejected("Academic student shadow unavailable");
        return count;
    }
    public long membershipCount() {
        Long count=db.queryForObject(
            "SELECT COUNT(*) FROM academic_class_memberships_shadow WHERE event_kind='UPSERT'",
            Long.class);
        if(count==null || count<0)throw rejected("Academic roster shadow unavailable");
        return count;
    }

    private record Receipt(String type,String id,String kind,long revision,
        String hash,String payload) {}

    @Transactional
    public void apply(AcademicRosterSourceClient.Event event) {
        if(event==null || event.eventId()<1 || event.sourceRevision()<1 ||
            event.entityId()==null ||
            !event.entityId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !Set.of("STUDENT","MEMBERSHIP").contains(event.entityType()) ||
            !Set.of("UPSERT","DELETE").contains(event.eventKind()) ||
            event.payloadJson()==null || event.payloadHash()==null ||
            !event.payloadHash().matches("[0-9a-f]{64}") ||
            !AcademicSessionSourceRepository.sha256(event.payloadJson())
                .equals(event.payloadHash()))
            throw rejected("Invalid Core student roster event identity or SHA-256");

        final JsonNode payload;
        try { payload=mapper.readTree(event.payloadJson()); }
        catch(Exception ex) {throw rejected("Invalid Core roster JSON");}
        if(payload==null || !payload.isObject())
            throw rejected("Missing Core roster object payload");
        boolean student="STUDENT".equals(event.entityType());
        String id=payload.path(student?"student_id":"class_student_id").asText("");
        String code=payload.path("student_code").asText("");
        String name=payload.path("full_name").asText("");
        String status=payload.path("status").asText("");
        String classId=payload.path("class_id").asText("");
        String studentId=payload.path("student_id").asText("");
        if(!event.entityId().equals(id) || status.isBlank() ||
            (student && (code.isBlank() || name.isBlank())) ||
            (!student && (classId.isBlank() || studentId.isBlank())) ||
            (!student && (!classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                !studentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))))
            throw rejected("Incomplete or inconsistent Core student roster payload");

        // A committed replay must match its immutable original, including
        // canonical JSON, hash and source revision. Never rewrite history.
        int added=db.update(
            "INSERT INTO academic_roster_source_receipts "+
            "(event_id,entity_type,entity_id,event_kind,source_revision,"+
            "payload_hash,source_payload) "+
            "VALUES(?,?,?,?,?,?,CAST(? AS JSONB)) "+
            "ON CONFLICT(event_id) DO NOTHING",
            event.eventId(),event.entityType(),event.entityId(),
            event.eventKind(),event.sourceRevision(),event.payloadHash(),
            event.payloadJson());
        if(added==0) {
            List<Receipt> previous=db.query(
                "SELECT entity_type,entity_id,event_kind,source_revision,"+
                "payload_hash,source_payload::text "+
                "FROM academic_roster_source_receipts WHERE event_id=? FOR UPDATE",
                (rs,i)->new Receipt(rs.getString(1),rs.getString(2),rs.getString(3),
                    rs.getLong(4),rs.getString(5),rs.getString(6)),event.eventId());
            if(previous.size()!=1 || !event.entityType().equals(previous.get(0).type()) ||
                !event.entityId().equals(previous.get(0).id()) ||
                !event.eventKind().equals(previous.get(0).kind()) ||
                event.sourceRevision()!=previous.get(0).revision() ||
                !event.payloadHash().equals(previous.get(0).hash()))
                throw rejected("Academic roster event ID conflicts with stored evidence");
            try {
                if(!payload.equals(mapper.readTree(previous.get(0).payload())))
                    throw rejected("Academic roster event payload changed");
            } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw rejected("Academic stored roster evidence is invalid");
            }
        } else if(added!=1) {
            throw rejected("Ambiguous Academic student roster event insert");
        }
        if(student) {
            db.update(
                "INSERT INTO academic_students_shadow "+
                "(student_id,student_code,full_name,status,event_kind,"+
                "source_event_id,source_revision,source_payload) "+
                "VALUES(?,?,?,?,?,?,?,CAST(? AS JSONB)) "+
                "ON CONFLICT(student_id) DO UPDATE SET "+
                "student_code=EXCLUDED.student_code,full_name=EXCLUDED.full_name,"+
                "status=EXCLUDED.status,event_kind=EXCLUDED.event_kind,"+
                "source_event_id=EXCLUDED.source_event_id,"+
                "source_revision=EXCLUDED.source_revision,"+
                "source_payload=EXCLUDED.source_payload,updated_at=NOW() "+
                "WHERE academic_students_shadow.source_event_id<EXCLUDED.source_event_id",
                id,code,name,status,event.eventKind(),event.eventId(),
                event.sourceRevision(),event.payloadJson());
        } else {
            db.update(
                "INSERT INTO academic_class_memberships_shadow "+
                "(class_student_id,class_id,student_id,membership_status,"+
                "event_kind,source_event_id,source_revision,source_payload) "+
                "VALUES(?,?,?,?,?,?,?,CAST(? AS JSONB)) "+
                "ON CONFLICT(class_student_id) DO UPDATE SET "+
                "class_id=EXCLUDED.class_id,student_id=EXCLUDED.student_id,"+
                "membership_status=EXCLUDED.membership_status,"+
                "event_kind=EXCLUDED.event_kind,"+
                "source_event_id=EXCLUDED.source_event_id,"+
                "source_revision=EXCLUDED.source_revision,"+
                "source_payload=EXCLUDED.source_payload,updated_at=NOW() "+
                "WHERE academic_class_memberships_shadow.source_event_id<EXCLUDED.source_event_id",
                id,classId,studentId,status,event.eventKind(),
                event.eventId(),event.sourceRevision(),event.payloadJson());
        }
        db.update("UPDATE academic_roster_history_cursor "+
                "SET last_event_id=GREATEST(last_event_id,?),updated_at=NOW() "+
                "WHERE singleton=TRUE",event.eventId());
    }
}

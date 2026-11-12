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

    /**
     * Compare all latest immutable events to both typed Academic shadows.
     * COUNT alone misses swapped IDs, payload tampering and resurrected DELETEs.
     */
    public long latestDivergence() {
        Long count=db.queryForObject(
            "WITH latest AS (SELECT DISTINCT ON(entity_type,entity_id) "+
            "entity_type,entity_id,event_id,event_kind,source_revision,source_payload "+
            "FROM academic_roster_source_receipts ORDER BY entity_type,entity_id,event_id DESC) "+
            "SELECT COUNT(*) FROM ("+
            " SELECT COALESCE(l.entity_id,s.student_id) AS id "+
            " FROM (SELECT * FROM latest WHERE entity_type='STUDENT') l "+
            " FULL OUTER JOIN academic_students_shadow s ON s.student_id=l.entity_id "+
            " WHERE l.entity_id IS NULL OR s.student_id IS NULL "+
            " OR s.event_kind IS DISTINCT FROM l.event_kind "+
            " OR s.source_event_id IS DISTINCT FROM l.event_id "+
            " OR s.source_revision IS DISTINCT FROM l.source_revision "+
            " OR s.source_payload IS DISTINCT FROM l.source_payload "+
            " OR s.student_code IS DISTINCT FROM l.source_payload->>'student_code' "+
            " OR s.full_name IS DISTINCT FROM l.source_payload->>'full_name' "+
            " OR s.status IS DISTINCT FROM l.source_payload->>'status' "+
            " UNION ALL "+
            " SELECT COALESCE(l.entity_id,m.class_student_id) AS id "+
            " FROM (SELECT * FROM latest WHERE entity_type='MEMBERSHIP') l "+
            " FULL OUTER JOIN academic_class_memberships_shadow m ON m.class_student_id=l.entity_id "+
            " WHERE l.entity_id IS NULL OR m.class_student_id IS NULL "+
            " OR m.event_kind IS DISTINCT FROM l.event_kind "+
            " OR m.source_event_id IS DISTINCT FROM l.event_id "+
            " OR m.source_revision IS DISTINCT FROM l.source_revision "+
            " OR m.source_payload IS DISTINCT FROM l.source_payload "+
            " OR m.class_id IS DISTINCT FROM l.source_payload->>'class_id' "+
            " OR m.student_id IS DISTINCT FROM l.source_payload->>'student_id' "+
            " OR m.membership_status IS DISTINCT FROM l.source_payload->>'status' "+
            " UNION ALL "+
            " SELECT COALESCE(l.entity_id,t.student_target_id) AS id "+
            " FROM (SELECT * FROM latest WHERE entity_type='TARGET') l "+
            " FULL OUTER JOIN academic_student_targets_shadow t ON t.student_target_id=l.entity_id "+
            " WHERE l.entity_id IS NULL OR t.student_target_id IS NULL "+
            " OR t.event_kind IS DISTINCT FROM l.event_kind "+
            " OR t.source_event_id IS DISTINCT FROM l.event_id "+
            " OR t.source_revision IS DISTINCT FROM l.source_revision "+
            " OR t.source_payload IS DISTINCT FROM l.source_payload "+
            " OR t.student_id IS DISTINCT FROM l.source_payload->>'student_id' "+
            " OR t.course_id IS DISTINCT FROM l.source_payload->>'course_id' "+
            " OR t.target_type IS DISTINCT FROM l.source_payload->>'target_type' "+
            " OR t.target_value IS DISTINCT FROM (l.source_payload->>'target_value')::numeric "+
            " UNION ALL "+
            " SELECT COALESCE(l.entity_id,r.student_result_id) AS id "+
            " FROM (SELECT * FROM latest WHERE entity_type='RESULT') l "+
            " FULL OUTER JOIN academic_student_results_shadow r ON r.student_result_id=l.entity_id "+
            " WHERE l.entity_id IS NULL OR r.student_result_id IS NULL "+
            " OR r.event_kind IS DISTINCT FROM l.event_kind "+
            " OR r.source_event_id IS DISTINCT FROM l.event_id "+
            " OR r.source_revision IS DISTINCT FROM l.source_revision "+
            " OR r.source_payload IS DISTINCT FROM l.source_payload "+
            " OR r.student_id IS DISTINCT FROM l.source_payload->>'student_id' "+
            " OR r.class_id IS DISTINCT FROM l.source_payload->>'class_id' "+
            " OR r.score IS DISTINCT FROM (l.source_payload->>'score')::numeric "+
            " OR r.evaluated_by IS DISTINCT FROM l.source_payload->>'evaluated_by' "+
            " OR r.evaluated_at IS DISTINCT FROM (l.source_payload->>'evaluated_at')::timestamptz "+
            ") mismatches",Long.class);
        if(count==null || count<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic roster latest-row reconciliation failed");
        return count;
    }

    /** Full historical parity, including tombstones and every source digest. */
    public boolean matchesEvent(AcademicRosterSourceClient.Event event) {
        if(event==null || event.payloadJson()==null ||
            !AcademicSessionSourceRepository.sha256(event.payloadJson())
                .equals(event.payloadHash()))return false;
        List<Receipt> rows=db.query(
            "SELECT entity_type,entity_id,event_kind,source_revision,"+
            "payload_hash,source_payload::text FROM academic_roster_source_receipts "+
            "WHERE event_id=?",
            (rs,i)->new Receipt(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getLong(4),rs.getString(5),rs.getString(6)),event.eventId());
        if(rows.size()!=1)return false;
        var row=rows.get(0);
        try {
            return event.entityType().equals(row.type()) &&
                event.entityId().equals(row.id()) &&
                event.eventKind().equals(row.kind()) &&
                event.sourceRevision()==row.revision() &&
                event.payloadHash().equals(row.hash()) &&
                mapper.readTree(event.payloadJson()).equals(mapper.readTree(row.payload()));
        } catch(Exception ex) {return false;}
    }

    /** FE StudentSummary fields, never an unfiltered raw Core JSON payload. */
    @Transactional(readOnly=true)
    public String adminStudentListJson() {
        String result=db.queryForObject(
            "SELECT COALESCE(jsonb_agg(jsonb_build_object("+
            "'studentId',student_id,'studentCode',student_code,"+
            "'fullName',full_name,'email',source_payload->>'email',"+
            "'phone',source_payload->>'phone','level',source_payload->>'level',"+
            "'status',status) ORDER BY full_name,student_id),"+
            "'[]'::jsonb)::text FROM academic_students_shadow "+
            "WHERE event_kind='UPSERT'",String.class);
        if(result==null || result.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic student list unavailable");
        return result;
    }

    /**
     * Exact StudentDetailResponse.targets contract backed solely by
     * Academic-owned typed PostgreSQL. DELETE tombstones are never returned.
     * Full immutable source history parity is checked by the caller.
     */
    @Transactional(readOnly=true)
    public String adminStudentTargetsJson(String studentId) {
        String result=db.queryForObject(
            "SELECT COALESCE(jsonb_agg(jsonb_build_object("+
            "'id',student_target_id,'courseId',course_id,"+
            "'targetType',target_type,'targetValue',target_value) "+
            "ORDER BY course_id,target_type,student_target_id),"+
            "'[]'::jsonb)::text FROM academic_student_targets_shadow "+
            "WHERE student_id=? AND event_kind='UPSERT'",
            String.class,studentId);
        if(result==null || result.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic Student Targets projection unavailable");
        return result;
    }

    /**
     * Exact StudentDetailResponse.results contract. Assignment, exam and
     * feedback are copied from the immutable source event JSON; score,
     * evaluator and timestamp are from Academic's verified typed shadow.
     */
    @Transactional(readOnly=true)
    public String adminStudentResultsJson(String studentId) {
        String result=db.queryForObject(
            "SELECT COALESCE(jsonb_agg(jsonb_build_object("+
            "'id',student_result_id,'classId',class_id,"+
            "'assignmentId',source_payload->>'assignment_id',"+
            "'examId',source_payload->>'exam_id',"+
            "'score',score,'feedback',source_payload->>'feedback',"+
            "'evaluatedBy',evaluated_by,"+
            "'evaluatedAt',evaluated_at) "+
            "ORDER BY evaluated_at DESC,student_result_id),"+
            "'[]'::jsonb)::text FROM academic_student_results_shadow "+
            "WHERE student_id=? AND event_kind='UPSERT'",
            String.class,studentId);
        if(result==null || result.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic Student Results projection unavailable");
        return result;
    }

    private record Receipt(String type,String id,String kind,long revision,
        String hash,String payload) {}

    @Transactional
    public void apply(AcademicRosterSourceClient.Event event) {
        if(event==null || event.eventId()<1 || event.sourceRevision()<1 ||
            event.entityId()==null ||
            !event.entityId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !Set.of("STUDENT","MEMBERSHIP","TARGET","RESULT").contains(event.entityType()) ||
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
        boolean membership="MEMBERSHIP".equals(event.entityType());
        boolean target="TARGET".equals(event.entityType());
        String identityField=student?"student_id":membership?"class_student_id":
            target?"student_target_id":"student_result_id";
        String id=payload.path(identityField).asText("");
        String code=payload.path("student_code").asText("");
        String name=payload.path("full_name").asText("");
        String status=payload.path("status").asText("");
        String classId=payload.path("class_id").asText("");
        String studentId=payload.path("student_id").asText("");
        String courseId=payload.path("course_id").asText("");
        String targetType=payload.path("target_type").asText("");
        String evaluatedBy=payload.path("evaluated_by").asText("");
        java.math.BigDecimal amount=null;
        java.time.OffsetDateTime evaluatedAt=null;
        if(target || "RESULT".equals(event.entityType())) {
            try {
                amount=new java.math.BigDecimal(payload.path(target?"target_value":"score")
                    .asText(""));
                if(amount.signum()<0 || amount.scale()>(target?2:2) ||
                    amount.precision()>(target?8:5))
                    throw new IllegalArgumentException("Invalid academic numeric scale");
                if(!target)
                    evaluatedAt=java.time.OffsetDateTime.parse(
                        payload.path("evaluated_at").asText(""));
            } catch(Exception ex) {
                throw rejected("Invalid Core Academic target/result value or timestamp");
            }
        }
        if(!event.entityId().equals(id) ||
            (student && (status.isBlank() || code.isBlank() || name.isBlank())) ||
            (membership && (status.isBlank() || classId.isBlank() ||
                studentId.isBlank())) ||
            (target && (studentId.isBlank() || courseId.isBlank() ||
                targetType.isBlank() || amount==null)) ||
            (!student && !membership && !target &&
                (studentId.isBlank() || classId.isBlank() ||
                 evaluatedBy.isBlank() || evaluatedAt==null)))
            throw rejected("Incomplete or inconsistent Core student/roster payload");

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
        } else if(membership) {
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
        if(target) {
            db.update(
                "INSERT INTO academic_student_targets_shadow "+
                "(student_target_id,student_id,course_id,target_type,target_value,"+
                "event_kind,source_event_id,source_revision,source_payload) "+
                "VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSONB)) "+
                "ON CONFLICT(student_target_id) DO UPDATE SET "+
                "student_id=EXCLUDED.student_id,course_id=EXCLUDED.course_id,"+
                "target_type=EXCLUDED.target_type,target_value=EXCLUDED.target_value,"+
                "event_kind=EXCLUDED.event_kind,source_event_id=EXCLUDED.source_event_id,"+
                "source_revision=EXCLUDED.source_revision,"+
                "source_payload=EXCLUDED.source_payload,updated_at=NOW() "+
                "WHERE academic_student_targets_shadow.source_event_id<EXCLUDED.source_event_id",
                id,studentId,courseId,targetType,amount,event.eventKind(),
                event.eventId(),event.sourceRevision(),event.payloadJson());
        } else if("RESULT".equals(event.entityType())) {
            db.update(
                "INSERT INTO academic_student_results_shadow "+
                "(student_result_id,student_id,class_id,score,evaluated_by,"+
                "evaluated_at,event_kind,source_event_id,source_revision,source_payload) "+
                "VALUES(?,?,?,?,?,CAST(? AS TIMESTAMPTZ),?,?,?,CAST(? AS JSONB)) "+
                "ON CONFLICT(student_result_id) DO UPDATE SET "+
                "student_id=EXCLUDED.student_id,class_id=EXCLUDED.class_id,"+
                "score=EXCLUDED.score,evaluated_by=EXCLUDED.evaluated_by,"+
                "evaluated_at=EXCLUDED.evaluated_at,event_kind=EXCLUDED.event_kind,"+
                "source_event_id=EXCLUDED.source_event_id,"+
                "source_revision=EXCLUDED.source_revision,"+
                "source_payload=EXCLUDED.source_payload,updated_at=NOW() "+
                "WHERE academic_student_results_shadow.source_event_id<EXCLUDED.source_event_id",
                id,studentId,classId,amount,evaluatedBy,evaluatedAt.toString(),
                event.eventKind(),event.eventId(),event.sourceRevision(),
                event.payloadJson());
        }
        db.update("UPDATE academic_roster_history_cursor "+
                "SET last_event_id=GREATEST(last_event_id,?),updated_at=NOW() "+
                "WHERE singleton=TRUE",event.eventId());
    }
}

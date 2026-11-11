package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One committed local import transaction owns: immutable receipt, latest
 * normalized Academic session source, and (for backfill) historical cursor.
 * A later source revision cannot be overwritten by an old retry.
 */
@Repository
public class AcademicSessionSourceRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private record Receipt(String sessionId,String classId,String kind,
        String hash,long revision) {}

    public AcademicSessionSourceRepository(JdbcTemplate db,ObjectMapper json) {
        this.db=db;this.json=json;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }

    public long historyCursor() {
        Long value=db.queryForObject(
            "SELECT last_event_id FROM academic_session_history_cursor WHERE singleton=TRUE",
            Long.class);
        if(value==null || value<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session history cursor unavailable");
        return value;
    }

    public long receiptCount() {
        Long value=db.queryForObject("SELECT COUNT(*) FROM academic_session_source_receipts",
            Long.class);
        if(value==null || value<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session receipts unavailable");
        return value;
    }
    public long latestCount() {
        Long value=db.queryForObject("SELECT COUNT(*) FROM academic_session_owned_projection",
            Long.class);
        if(value==null || value<0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session latest projection unavailable");
        return value;
    }

    @Transactional
    public void apply(AcademicSessionSourceClient.Event event,boolean historical) {
        if(event==null || event.eventId()<1 || event.sourceRevision()<1 ||
           event.classSessionId()==null ||
           !event.classSessionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
           event.classId()==null ||
           !event.classId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
           !Set.of("UPSERT","DELETE").contains(event.eventKind()) ||
           event.payloadJson()==null || event.payloadHash()==null ||
           !event.payloadHash().matches("[0-9a-f]{64}") ||
           !sha256(event.payloadJson()).equals(event.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Invalid Core Academic event identity or SHA-256");

        final JsonNode payload;
        try { payload=json.readTree(event.payloadJson()); }
        catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Malformed Academic session source JSON",ex);
        }
        if(payload==null || !payload.isObject() ||
           !event.classSessionId().equals(payload.path("class_session_id").asText(null)) ||
           !event.classId().equals(payload.path("class_id").asText(null)) ||
           !payload.hasNonNull("date") || !payload.hasNonNull("start_time") ||
           !payload.hasNonNull("end_time") || !payload.hasNonNull("status"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic event does not match a complete original Core lesson");

        int inserted=db.update(
            "INSERT INTO academic_session_source_receipts "+
            "(event_id,class_session_id,class_id,event_kind,source_revision,"+
            "payload_hash,source_payload) VALUES(?,?,?,?,?,?,CAST(? AS JSONB)) "+
            "ON CONFLICT(event_id) DO NOTHING",
            event.eventId(),event.classSessionId(),event.classId(),
            event.eventKind(),event.sourceRevision(),event.payloadHash(),
            event.payloadJson());
        if(inserted!=1) {
            List<Receipt> original=db.query(
                "SELECT class_session_id,class_id,event_kind,payload_hash,source_revision "+
                "FROM academic_session_source_receipts WHERE event_id=? FOR UPDATE",
                (rs,i)->new Receipt(rs.getString(1),rs.getString(2),
                    rs.getString(3),rs.getString(4),rs.getLong(5)),event.eventId());
            if(original.size()!=1 ||
               !event.classSessionId().equals(original.get(0).sessionId()) ||
               !event.classId().equals(original.get(0).classId()) ||
               !event.eventKind().equals(original.get(0).kind()) ||
               !event.payloadHash().equals(original.get(0).hash()) ||
               event.sourceRevision()!=original.get(0).revision())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Academic source event ID already committed with different evidence");
        }
        // The tombstone keeps the original payload for audit, but its kind
        // prevents it from ever being treated as a currently planned lesson.
        db.update(
            "INSERT INTO academic_session_owned_projection "+
            "(class_session_id,class_id,source_event_id,source_revision,event_kind,source_payload) "+
            "VALUES(?,?,?,?,?,CAST(? AS JSONB)) "+
            "ON CONFLICT(class_session_id) DO UPDATE SET "+
            "class_id=EXCLUDED.class_id,source_event_id=EXCLUDED.source_event_id,"+
            "source_revision=EXCLUDED.source_revision,event_kind=EXCLUDED.event_kind,"+
            "source_payload=EXCLUDED.source_payload,updated_at=NOW() "+
            "WHERE academic_session_owned_projection.source_event_id<EXCLUDED.source_event_id",
            event.classSessionId(),event.classId(),event.eventId(),
            event.sourceRevision(),event.eventKind(),event.payloadJson());
        if(historical) {
            db.update("UPDATE academic_session_history_cursor "+
                "SET last_event_id=GREATEST(last_event_id,?),updated_at=NOW() "+
                "WHERE singleton=TRUE",event.eventId());
        }
    }
}

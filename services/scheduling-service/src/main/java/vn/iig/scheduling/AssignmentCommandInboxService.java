package vn.iig.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scheduling-owned durable inbox for a future assignment command processor.
 * Phase 8b-3b intentionally has NO executor: STAGED is the only state created
 * by this service. Accepting a staging request never writes an assignment.
 * The Core monolith remains the sole mutation authority in this phase.
 */
@Service
public class AssignmentCommandInboxService {
    public record Request(String operation,String targetId,String requestKey,JsonNode payload) {}
    public record Receipt(String operation,String requestKey,String state,String requestHash) {}
    private record Stored(String state,String hash) {}

    private static final Set<String> OPERATIONS=Set.of(
        "ASSIGN_TEACHER","EDIT_TEACHER","CANCEL_TEACHER",
        "ASSIGN_STAFF","EDIT_STAFF","CANCEL_STAFF",
        "ASSIGN_TEACHER_BATCH","ASSIGN_SUPPORT_BATCH","ASSIGN_CLASS_RANGE");

    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AssignmentCommandInboxService(JdbcTemplate db,ObjectMapper mapper) {
        this.db=db;
        this.mapper=mapper;
    }

    @Transactional
    public Receipt stage(String username,String role,Request request) {
        if(username==null || username.isBlank() || role==null || role.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authenticated actor required");
        if(request==null || request.operation()==null || !OPERATIONS.contains(request.operation()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown assignment operation");
        boolean teacher=request.operation().contains("TEACHER");
        if(request.operation().equals("ASSIGN_CLASS_RANGE")) {
            // A range request can manage teachers or CS; it must contain the
            // explicitly selected role, not derive authorization from a key.
            if(request.payload()==null || !request.payload().hasNonNull("role"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Range role required");
            teacher="TEACHER".equalsIgnoreCase(request.payload().get("role").asText());
            if(!teacher && !"CS".equalsIgnoreCase(request.payload().get("role").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Range role invalid");
        }
        if(!((teacher && "TC".equals(role)) || (!teacher && "CM".equals(role))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Assignment role not authorized");
        if(request.requestKey()==null || !request.requestKey()
                .matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid command idempotency key");
        if((request.operation().startsWith("EDIT_") || request.operation().startsWith("CANCEL_"))
                && (request.targetId()==null || request.targetId().isBlank() || request.targetId().length()>50))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Assignment targetId required");
        if(!request.operation().startsWith("CANCEL_")
                && (request.payload()==null || !request.payload().isObject()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Assignment request JSON required");

        // Sorting object keys normalizes benign client field ordering without
        // changing array order (which is significant for session batches).
        String canonical=canonical(request);
        String digest=sha256(canonical);
        int inserted=db.update("INSERT INTO scheduling_assignment_command_inbox " +
            "(actor_username,operation,request_key,request_hash,payload,state) " +
            "VALUES(?,?,?,?,CAST(? AS jsonb),'STAGED') ON CONFLICT DO NOTHING",
            username,request.operation(),request.requestKey(),digest,canonical);
        if(inserted==1) return new Receipt(request.operation(),request.requestKey(),"STAGED",digest);

        List<Stored> existing=db.query(
            "SELECT state,request_hash FROM scheduling_assignment_command_inbox " +
            "WHERE actor_username=? AND operation=? AND request_key=? FOR UPDATE",
            (rs,i)->new Stored(rs.getString(1),rs.getString(2)),
            username,request.operation(),request.requestKey());
        if(existing.size()!=1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Assignment command receipt unavailable");
        Stored prior=existing.get(0);
        if(!digest.equals(prior.hash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Command key already used for different assignment request");
        return new Receipt(request.operation(),request.requestKey(),prior.state(),digest);
    }

    private String canonical(Request input) {
        try {
            TreeMap<String,Object> record=new TreeMap<>();
            record.put("operation",input.operation());
            record.put("targetId",input.targetId());
            record.put("payload",sorted(input.payload()));
            return mapper.writeValueAsString(record);
        } catch(JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid assignment JSON",ex);
        }
    }

    private Object sorted(JsonNode node) {
        if(node==null || node.isNull()) return null;
        if(node.isObject()) {
            TreeMap<String,Object> fields=new TreeMap<>();
            node.fields().forEachRemaining(entry->fields.put(entry.getKey(),sorted(entry.getValue())));
            return fields;
        }
        if(node.isArray()) {
            java.util.ArrayList<Object> values=new java.util.ArrayList<>();
            node.forEach(value->values.add(sorted(value)));
            return values;
        }
        return node;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}

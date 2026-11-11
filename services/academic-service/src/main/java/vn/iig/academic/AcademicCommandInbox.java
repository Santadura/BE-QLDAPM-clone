package vn.iig.academic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Local audit/receipt only: Core's matching atomic receipt decides success. */
@Repository
public class AcademicCommandInbox {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public AcademicCommandInbox(JdbcTemplate db,ObjectMapper json) {
        this.db=db;this.json=json;
    }
    @Transactional
    public void stage(String actor,String classId,String operation,String key,String hash) {
        db.update("INSERT INTO academic_session_command_inbox "+
            "(actor_username,class_id,operation,request_key,request_hash) "+
            "VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
            actor,classId,operation,key,hash);
        Map<String,Object> existing=db.queryForMap(
            "SELECT request_hash FROM academic_session_command_inbox "+
            "WHERE actor_username=? AND class_id=? AND operation=? AND request_key=? FOR UPDATE",
            actor,classId,operation,key);
        if(!hash.equals(existing.get("request_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key reused for a different Academic command");
    }
    @Transactional
    public void complete(String actor,String classId,String operation,
                         String key,String hash,JsonNode result) {
        if(result==null || !result.isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Core session command outcome");
        int updated=db.update("UPDATE academic_session_command_inbox "+
            "SET state='COMMITTED',response_payload=CAST(? AS JSONB),completed_at=NOW() "+
            "WHERE actor_username=? AND class_id=? AND operation=? AND "+
            "request_key=? AND request_hash=? AND state='PENDING'",
            result.toString(),actor,classId,operation,key,hash);
        if(updated==1)return;
        Map<String,Object> row=db.queryForMap(
            "SELECT request_hash,response_payload::text AS response_payload "+
            "FROM academic_session_command_inbox WHERE actor_username=? "+
            "AND class_id=? AND operation=? AND request_key=? FOR UPDATE",
            actor,classId,operation,key);
        try {
            JsonNode prior=json.readTree(String.valueOf(row.get("response_payload")));
            if(!hash.equals(row.get("request_hash")) || !result.equals(prior))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Academic receipt diverges from Core outcome");
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Corrupted Academic command receipt",ex);
        }
    }
}

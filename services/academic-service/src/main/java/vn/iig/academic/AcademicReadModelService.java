package vn.iig.academic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Academic read boundary with independent read-model persistence.
 * Core remains the class/session writer. Fetch from Core on EVERY request
 * (including after writes) and never fall back to an unverified cached array.
 */
@Service
public class AcademicReadModelService {
    public record Result(JsonNode payload,Instant synchronizedAt) {}
    private final AcademicCoreClient core;
    private final AcademicSnapshotRepository repo;
    private final ObjectMapper json;

    public AcademicReadModelService(AcademicCoreClient core,
                                    AcademicSnapshotRepository repo,ObjectMapper json) {
        this.core=core;this.repo=repo;this.json=json;
    }

    public Result planning(String bearer) {
        var actor=core.currentPrincipal(bearer);
        if(!Set.of("ROLE_TC","ROLE_CM","ROLE_ADMIN").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Planning list available only to TC, CM and ADMIN");
        return refresh(actor,"CLASS_PLANNING","all",core.planning(bearer));
    }

    public Result sessions(String classId,String bearer) {
        if(classId==null || classId.isBlank() || classId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid class ID");
        var actor=core.currentPrincipal(bearer);
        // Core enforces the nuanced managing-CS/teacher/current-assignment
        // ACL on the source endpoint, not merely the role listed in JWT.
        return refresh(actor,"PLANNED_SESSIONS",classId,
            core.plannedSessions(classId,bearer));
    }

    private Result refresh(AcademicCoreClient.Principal actor,String kind,
                           String resource,String source) {
        final JsonNode data;
        try {
            data=json.readTree(source);
        } catch(Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Core Academic JSON",e);
        }
        if(data==null || !data.isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Academic source must return a JSON array");
        String key=digest(actor.username()+"\n"+actor.role()+"\n"+kind+"\n"+resource);
        Instant saved=repo.save(key,actor.username(),actor.role(),kind,data.toString());
        return new Result(data,saved);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable",e);
        }
    }
}

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
        // Academic PostgreSQL is now the class-planning READ source. Core
        // only provides a cheap transactionally updated source revision; the
        // full JSON is fetched when that revision has actually changed.
        for(int attempt=0;attempt<3;attempt++) {
            long revision=core.planningVersion().revision();
            var cached=repo.loadPlanning();
            if(cached!=null && cached.sourceRevision()==revision) {
                if(core.planningVersion().revision()==revision)
                    return new Result(parseArray(cached.payload()),cached.synchronizedAt());
                continue;
            }
            var source=core.planningExport();
            if(source==null || source.rows()==null || !source.rows().isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Academic planning source must return a JSON array");
            if(source.revision()<revision)
                continue;
            var saved=repo.savePlanning(source.revision(),source.rows().toString());
            if(saved.sourceRevision()==core.planningVersion().revision())
                return new Result(parseArray(saved.payload()),saved.synchronizedAt());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Academic planning changed during source reconciliation");
    }

    public Result sessions(String classId,String bearer) {
        if(classId==null || classId.isBlank() || classId.length()>50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid class ID");
        core.currentPrincipal(bearer);
        // A cached class timetable is NEVER an authorization grant. Core
        // checks the same live CS/Teacher/class scopes on EVERY version
        // lookup, including both lookups on a cache hit.
        for(int attempt=0;attempt<3;attempt++) {
            long revision=core.sessionRevision(classId,bearer).revision();
            var cached=repo.loadSessions(classId);
            if(cached!=null && cached.sourceRevision()==revision) {
                if(core.sessionRevision(classId,bearer).revision()==revision)
                    return new Result(parseArray(cached.payload()),cached.synchronizedAt());
                continue;
            }
            var source=core.sessionExport(classId,bearer);
            if(source==null || source.rows()==null || !source.rows().isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Core class-session snapshot must be an array");
            if(source.revision()<revision)
                continue;
            var saved=repo.saveSessions(classId,source.revision(),source.rows().toString());
            if(saved.sourceRevision()==core.sessionRevision(classId,bearer).revision())
                return new Result(parseArray(saved.payload()),saved.synchronizedAt());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Academic lessons changed during read-model reconciliation");
    }

    private JsonNode parseArray(String source) {
        try {
            JsonNode data=json.readTree(source);
            if(data!=null && data.isArray()) return data;
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Malformed Academic planning read model",ex);
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Academic planning snapshot must be an array");
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

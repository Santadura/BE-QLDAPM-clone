package vn.iig.academic;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Independent command ingress; NOT yet independent Academic write ownership. */
@Service
public class AcademicCommandService {
    private final AcademicCoreClient identity;
    private final AcademicWriteClient core;
    private final AcademicCommandInbox inbox;
    private final AcademicSessionSourceClient fences;
    private final AcademicRosterSourceClient rosterFences;
    public AcademicCommandService(AcademicCoreClient identity,
            AcademicWriteClient core,AcademicCommandInbox inbox,
            AcademicSessionSourceClient fences,AcademicRosterSourceClient rosterFences) {
        this.identity=identity;this.core=core;this.inbox=inbox;this.fences=fences;
        this.rosterFences=rosterFences;
    }

    private void requireLegacyCoreOwnership() {
        // A previously entered maintenance freeze must reject new commands
        // BEFORE an independent PENDING inbox receipt is staged. Core's SQL
        // triggers are the final race-safe writer fence at transaction commit.
        var state=fences.writerFence();
        // Session writes may affect class/student enrollments. A roster-only
        // maintenance freeze must stop NEW commands before local inbox staging;
        // Core's SQL session trigger is still the final concurrency fence.
        var roster=rosterFences.writerFence();
        if(roster==null || !"CORE".equals(roster.mode()) || roster.generation()<1 ||
                !"CORE".equals(state.academicMode()) ||
                !"CORE".equals(state.attendanceMode()) ||
                !"CORE".equals(state.schedulingMode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic/Roster/Scheduling/Attendance maintenance freeze: new Core writes disabled");
    }
    private String hash(String operation,String classId,JsonNode body) {
        try {
            String canonical="v1\n"+operation+"\n"+classId+"\n"+body.toString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
    public JsonNode execute(String operation,String classId,JsonNode body,
                            String bearer,String key) {
        if(classId==null || !classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            !Set.of("PLAN","RESCHEDULE").contains(operation) || body==null ||
            ("PLAN".equals(operation)?!body.isArray():!body.isObject()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid Academic mutation payload");
        var actor=identity.currentPrincipal(bearer);
        if(!Set.of("ROLE_ADMIN","ROLE_CS").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only Admin or managing CS may edit class sessions");
        requireLegacyCoreOwnership();
        if(key==null)return core.command(operation,classId,body,bearer,null);
        if(!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Idempotency-Key must be 1-128 safe ASCII characters");
        String digest=hash(operation,classId,body);
        inbox.stage(actor.username(),classId,operation,key,digest);
        // Never serve a saved response while Core might revoke the caller.
        // A lost Core response leaves PENDING; caller retries the SAME key.
        JsonNode result=core.command(operation,classId,body,bearer,key);
        inbox.complete(actor.username(),classId,operation,key,digest,result);
        return result;
    }
}

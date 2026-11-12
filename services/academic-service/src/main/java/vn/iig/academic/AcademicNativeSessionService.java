package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private non-authoritative native timetable integration. All independent
 * source and downstream receipts must be frozen and stable, before AND after
 * the Academic DB commit. Never routes a business mutation to Core.
 */
@Service
public class AcademicNativeSessionService {
    private final AcademicCoreClient core;
    private final AcademicFullCutoverReadiness readiness;
    private final AcademicNativeSessionRepository nativeSessions;

    public AcademicNativeSessionService(AcademicCoreClient core,
            AcademicFullCutoverReadiness readiness,
            AcademicNativeSessionRepository nativeSessions) {
        this.core=core;this.readiness=readiness;this.nativeSessions=nativeSessions;
    }
    private record ClassFacts(String status,LocalDate start,LocalDate end,long revision) {}
    private String admin(String bearer) {
        var actor=core.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()) ||
            actor.username()==null || actor.username().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Admin required for independent Academic session rehearsal");
        return actor.username();
    }
    private ClassFacts facts(String classId) {
        if(classId==null || !classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid Academic class ID required");
        var snapshot=core.planningExport();
        JsonNode found=null;
        for(var row:snapshot.rows()) {
            if(classId.equals(row.path("classId").asText(null))) {
                if(found!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Duplicate Academic class in Core snapshot");
                found=row;
            }
        }
        if(found==null)throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Only source-verified READY/RUNNING classes may be activated");
        try {
            String status=found.path("status").asText();
            if(!Set.of("READY","RUNNING").contains(status))
                throw new IllegalArgumentException("class status");
            return new ClassFacts(status,
                LocalDate.parse(found.path("startDate").asText()),
                LocalDate.parse(found.path("endDate").asText()),
                snapshot.revision());
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic class status/date evidence malformed",ex);
        }
    }

    @Transactional
    public long activate(String classId,String bearer) {
        String actor=admin(bearer);
        var before=readiness.verifyStaging();
        var classFacts=facts(classId);
        long rows=nativeSessions.activate(new AcademicNativeSessionRepository.Activation(
            classId,classFacts.status(),classFacts.start(),classFacts.end(),
            before.sessionGeneration(),before.rosterGeneration(),
            before.sessionLastEventId(),actor));
        if(!before.equals(readiness.verifyStaging()) || !classFacts.equals(facts(classId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic snapshot/fence changed during native activation; rolling back");
        return rows;
    }

    @Transactional
    public JsonNode execute(String operation,String classId,JsonNode body,
            String bearer,String key) {
        String actor=admin(bearer);
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native command requires an Idempotency-Key");
        var before=readiness.verifyStaging();
        var classFacts=facts(classId);
        var result=nativeSessions.execute(actor,classId,operation,key,body,
            before.sessionGeneration(),before.rosterGeneration());
        if(!before.equals(readiness.verifyStaging()) || !classFacts.equals(facts(classId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic snapshot/fence changed during native mutation; rolling back");
        return result;
    }
}

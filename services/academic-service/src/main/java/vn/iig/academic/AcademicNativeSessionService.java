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
    private final AcademicNativeCommandClaimClient claims;

    public AcademicNativeSessionService(AcademicCoreClient core,
            AcademicFullCutoverReadiness readiness,
            AcademicNativeSessionRepository nativeSessions,
            AcademicNativeCommandClaimClient claims) {
        this.core=core;this.readiness=readiness;this.nativeSessions=nativeSessions;
        this.claims=claims;
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
        if(snapshot==null || snapshot.rows()==null || !snapshot.rows().isArray() ||
            snapshot.revision()<1)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic class snapshot unavailable for native mutation");
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

    /**
     * Core acquires an OPEN claim before the independent Academic PostgreSQL
     * transaction. Only the exact committed immutable outbox permits its
     * subsequent RELEASE. A lost HTTP response must strand OPEN, blocking
     * Core's timetable and roster handback until verified recovery.
     *
     * Do NOT annotate this orchestration with @Transactional: release must
     * happen AFTER nativeSessions.execute has committed in a separate local
     * Academic transaction, not before the Spring proxy commits.
     */
    public JsonNode execute(String operation,String classId,JsonNode body,
            String bearer,String key) {
        String actor=admin(bearer);
        if(key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic native command requires an Idempotency-Key");
        String digest=nativeSessions.commandDigest(operation,classId,body);
        var before=readiness.verifyStaging();
        var classFacts=facts(classId);
        var claim=new AcademicNativeCommandClaimClient.Claim(
            actor,classId,operation,key,digest,before.sessionGeneration(),
            before.rosterGeneration(),before.attendanceGeneration(),
            before.schedulingGeneration());
        var receipt=claims.acquire(claim);
        // A terminal Core claim permits only an exact immutable replay.
        if("RELEASED".equals(receipt.state())) {
            var original=nativeSessions.committedEvidence(
                actor,classId,operation,key,digest);
            if(receipt.eventId()==null ||
                !original.eventId().equalsIgnoreCase(receipt.eventId()) ||
                !original.eventHash().equals(receipt.outboxHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Released native Core claim differs from original Academic event");
            return original.response();
        }
        // Native repository commits rows + receipt + outbox atomically.
        JsonNode result=nativeSessions.execute(actor,classId,operation,key,body,
            before.sessionGeneration(),before.rosterGeneration());
        var original=nativeSessions.committedEvidence(
            actor,classId,operation,key,digest);
        if(!result.equals(original.response()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic response changed after independent local commit");
        if(!before.equals(readiness.verifyStaging()) ||
            !classFacts.equals(facts(classId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic frozen source changed after commit; Core claim stays OPEN");
        claims.release(new AcademicNativeCommandClaimClient.Release(
            claim,original.eventId(),original.eventHash()));
        // A separate local receipt records Core's exact RELEASED evidence.
        // A crash at this edge is repaired by AcademicNativeClaimHandoffRecovery.
        nativeSessions.recordClaimHandoff(
            new AcademicNativeSessionRepository.PendingHandoff(
                actor,classId,operation,key,digest,original.eventId(),
                original.eventHash(),before.sessionGeneration(),
                before.rosterGeneration()));
        return original.response();
    }
}

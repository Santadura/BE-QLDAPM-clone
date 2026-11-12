package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Strangler StudentDetail GET: Core STILL owns all writes, live account/class
 * authorization, class descriptions, and audit. Only the specifically enabled
 * ADMIN fields are sourced from separately persisted Academic PostgreSQL.
 * TEACHER is always served Core's original class/course-filtered response.
 * Every independent read fails closed on missing/divergent history, rather
 * than silently falling back to potentially stale or wider-scope data.
 */
@Service
public class AcademicRosterStudentDetailReadService {
    private final AcademicCoreClient identity;
    private final AcademicRosterReadiness parity;
    private final AcademicRosterSourceRepository academic;
    private final ObjectMapper json;
    private final boolean targetsEnabled;
    private final boolean resultsEnabled;

    public AcademicRosterStudentDetailReadService(AcademicCoreClient identity,
            AcademicRosterReadiness parity,
            AcademicRosterSourceRepository academic,ObjectMapper json,
            @Value("${iig.academic.student-targets-read-enabled:false}") boolean targetsEnabled,
            @Value("${iig.academic.student-results-read-enabled:false}") boolean resultsEnabled) {
        this.identity=identity;this.parity=parity;this.academic=academic;
        this.json=json;this.targetsEnabled=targetsEnabled;
        this.resultsEnabled=resultsEnabled;
    }

    public JsonNode detail(String studentId,String bearer) {
        // Core enforces 404, revoked JWT and TEACHER class visibility BEFORE
        // any independent student data is accessed.
        ObjectNode response=parseDetail(identity.studentDetail(studentId,bearer),studentId);
        if(!targetsEnabled && !resultsEnabled)return response;

        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()))
            return response; // strictly preserve existing Teacher scoped fields

        var before=parity.verify();
        if(targetsEnabled)
            response.set("targets",
                parseArray(academic.adminStudentTargetsJson(studentId),"targets"));
        if(resultsEnabled)
            response.set("results",
                parseArray(academic.adminStudentResultsJson(studentId),"results"));
        var after=parity.verify();
        if(!before.equals(after))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Student Target/Result history changed during Academic read");
        return response;
    }

    private ObjectNode parseDetail(String payload,String studentId) {
        try {
            JsonNode value=json.readTree(payload);
            if(!(value instanceof ObjectNode object) ||
                !studentId.equals(value.path("studentId").asText()) ||
                !value.path("targets").isArray() ||
                !value.path("results").isArray() ||
                !value.path("classes").isArray() ||
                !value.path("auditLogs").isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Core StudentDetail contract incomplete or identity changed");
            return object;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Core student detail JSON",ex);
        }
    }

    private JsonNode parseArray(String payload,String field) {
        try {
            JsonNode value=json.readTree(payload);
            if(value==null || !value.isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Academic "+field+" projection contract incomplete");
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Academic "+field+" projection JSON",ex);
        }
    }
}

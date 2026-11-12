package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Only ADMIN reads the independent Academic student projection. TEACHER stays
 * on the Core permission-scoped API, not a locally inferred class ACL.
 * Disabled flag always delegates to the original Core API.
 */
@Service
public class AcademicRosterStudentReadService {
    private final AcademicCoreClient identity;
    private final AcademicRosterReadiness parity;
    private final AcademicRosterSourceRepository local;
    private final ObjectMapper json;
    private final boolean enabled;

    public AcademicRosterStudentReadService(AcademicCoreClient identity,
            AcademicRosterReadiness parity,AcademicRosterSourceRepository local,
            ObjectMapper json,
            @Value("${iig.academic.student-list-read-enabled:false}") boolean enabled) {
        this.identity=identity;this.parity=parity;this.local=local;
        this.json=json;this.enabled=enabled;
    }

    public JsonNode list(String bearer) {
        final String payload;
        if(!enabled) {
            payload=identity.students(bearer); // existing Core ACL and contract
        } else {
            var principal=identity.currentPrincipal(bearer);
            if("ROLE_ADMIN".equals(principal.role())) {
                // Validate the entire source before AND after the snapshot.
                var before=parity.verify();
                payload=local.adminStudentListJson();
                var after=parity.verify();
                if(!before.equals(after))
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Academic student source changed during authorized read");
            } else {
                payload=identity.students(bearer); // TEACHER original scoped Core path
            }
        }
        try {
            JsonNode value=json.readTree(payload);
            if(value==null || !value.isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid StudentSummary API response");
            return value;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Malformed student list response",ex);
        }
    }
}

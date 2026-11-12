package vn.iig.academic;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional HTTPS/HTTP-only Core adapter. Academic has NO Core DB account.
 * Fetch the live source on every GET until a durable Academic event stream
 * supports independent reads. Never serve a stale local snapshot on errors.
 */
@Component
public class AcademicCoreClient {
    public record Principal(String username,String role) {}
    private final RestClient client;
    private final String internalKey;

    public AcademicCoreClient(@Value("${iig.core.url}") String coreUrl,
                              @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("ACADEMIC_INTERNAL_API_KEY required");
        this.internalKey=internalKey;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public Principal currentPrincipal(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<15)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        final Map<?,?> account;
        try {
            account=client.get().uri("/api/me")
                .header("Authorization",bearer)
                .retrieve().body(Map.class);
        } catch(HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core identity source unavailable",ex);
        }
        if(account==null || !(account.get("username") instanceof String username)
                || username.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid identity");
        Object raw=account.get("authorities");
        if(!(raw instanceof List<?> authorities))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Role missing");
        String role=authorities.stream()
            .filter(Map.class::isInstance).map(Map.class::cast)
            .map(a->String.valueOf(a.get("authority")))
            .filter(a->a.startsWith("ROLE_")).findFirst().orElse(null);
        if(role==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Role missing");
        return new Principal(username,role);
    }

    public record PlanningRevision(long revision) {}
    public record PlanningExport(long revision,
        com.fasterxml.jackson.databind.JsonNode rows) {}

    public PlanningRevision planningVersion() {
        try {
            PlanningRevision value=client.get()
                .uri("/internal/academic/planning/version")
                .header("X-Internal-Key",internalKey)
                .retrieve().body(PlanningRevision.class);
            if(value==null || value.revision()<1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Academic planning source revision missing");
            return value;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic planning revision source unavailable",ex);
        }
    }

    public PlanningExport planningExport() {
        try {
            PlanningExport value=client.get()
                .uri("/internal/academic/planning/snapshot")
                .header("X-Internal-Key",internalKey)
                .retrieve().body(PlanningExport.class);
            if(value==null || value.revision()<1 || value.rows()==null ||
                    !value.rows().isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Academic planning source snapshot malformed");
            return value;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic planning source snapshot unavailable",ex);
        }
    }


    public record SessionRevision(long revision) {}
    public record SessionExport(long revision,
        com.fasterxml.jackson.databind.JsonNode rows) {}

    /**
     * Every version request crosses Core's exact class visibility policy.
     * This includes cache hits: an expired or revoked CS/Teacher must never
     * be served independently cached timetable data.
     */
    public SessionRevision sessionRevision(String classId,String bearer) {
        try {
            SessionRevision result=client.get()
                .uri("/internal/academic/classes/{id}/sessions/revision",classId)
                .header("X-Internal-Key",internalKey)
                .header("Authorization",bearer)
                .retrieve().body(SessionRevision.class);
            if(result==null || result.revision()<1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Missing Core class-session revision");
            return result;
        } catch(HttpClientErrorException ex) {
            throw sessionAccessError(ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic lesson revision source unavailable",ex);
        }
    }

    public SessionExport sessionExport(String classId,String bearer) {
        try {
            SessionExport result=client.get()
                .uri("/internal/academic/classes/{id}/sessions/snapshot",classId)
                .header("X-Internal-Key",internalKey)
                .header("Authorization",bearer)
                .retrieve().body(SessionExport.class);
            if(result==null || result.revision()<1 || result.rows()==null ||
                    !result.rows().isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid Core class-session snapshot");
            return result;
        } catch(HttpClientErrorException ex) {
            throw sessionAccessError(ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic lesson snapshot source unavailable",ex);
        }
    }

    private ResponseStatusException sessionAccessError(HttpClientErrorException ex) {
        return switch(ex.getStatusCode().value()) {
            case 401 -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Current Core session is invalid",ex);
            case 403 -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current Core class visibility is denied",ex);
            default -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Academic class-session source unavailable",ex);
        };
    }

    /** Existing Core student detail enforces CURRENT user and class ACL. */
    public String studentDetail(String studentId,String bearer) {
        if(studentId==null || !studentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid student ID required");
        return liveGet("/api/students/{classId}",bearer,studentId);
    }

    public String students(String bearer) {
        return liveGet("/api/students",bearer,null);
    }

    public String planning(String bearer) {
        return liveGet("/api/class-planning",bearer,null);
    }

    public String plannedSessions(String classId,String bearer) {
        return liveGet("/api/classes/{classId}/planned-sessions",bearer,classId);
    }

    private String liveGet(String path,String bearer,String id) {
        try {
            String response=id==null
                ? client.get().uri(path).header("Authorization",bearer)
                    .retrieve().body(String.class)
                : client.get().uri(builder->builder.path(path).build(id))
                    .header("Authorization",bearer)
                    .retrieve().body(String.class);
            if(response==null || response.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty Academic source response");
            return response;
        } catch(HttpClientErrorException ex) {
            int status=ex.getStatusCode().value();
            if(status==401) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session expired");
            if(status==403) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Class access denied");
            if(status==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Class not found");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic source rejected request",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic live source unavailable",ex);
        }
    }
}

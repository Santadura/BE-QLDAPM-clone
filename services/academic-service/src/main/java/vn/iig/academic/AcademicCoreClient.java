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

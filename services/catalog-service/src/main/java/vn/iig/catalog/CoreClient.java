package vn.iig.catalog;

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

/** HTTP boundary, not shared database access. JWT remains centrally checked. */
@Component
public class CoreClient {
    private final RestClient client;
    private final String internalKey;

    public CoreClient(@Value("${iig.core.url}") String baseUrl,
                      @Value("${iig.core.internal-key}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalArgumentException("CATALOG_INTERNAL_API_KEY must be configured");
        }
        this.internalKey = internalKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public List<Course> exportLegacy() {
        Course[] rows = client.get().uri("/internal/catalog/export")
            .header("X-Internal-Key", internalKey).retrieve().body(Course[].class);
        return rows == null ? List.of() : List.of(rows);
    }

    public void project(Course course) {
        client.post().uri("/internal/catalog/project")
            .header("X-Internal-Key", internalKey).body(course)
            .retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() < 16) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        }
        try {
            Map<String, Object> claims = client.get().uri("/api/me")
                .header("Authorization", authorization).retrieve().body(Map.class);
            if (claims == null || claims.get("username") == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
            }
            return claims;
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Identity verification unavailable");
        }
    }

    public void requireAdmin(String authorization) {
        Map<String, Object> account = authenticate(authorization);
        Object authorities = account.get("authorities");
        if (!(authorities instanceof List<?> list)
                || list.stream().noneMatch(item -> item instanceof Map<?,?> role
                    && "ROLE_ADMIN".equals(role.get("authority")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin required");
        }
    }
}

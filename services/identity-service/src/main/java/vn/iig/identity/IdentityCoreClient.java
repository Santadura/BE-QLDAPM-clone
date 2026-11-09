package vn.iig.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional migration port. Core retains account/role ownership until
 * its account/profile writes are extracted. Every login fetches CURRENT
 * state so a password change/lock cannot authenticate from a stale cache.
 */
@Component
public class IdentityCoreClient {
    public record Snapshot(String username, String email, String passwordHash,
                           String role, String status) {}

    private final RestClient client;
    private final String internalKey;

    public IdentityCoreClient(@Value("${iig.core.url}") String coreUrl,
                              @Value("${iig.core.internal-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_INTERNAL_API_KEY must be configured");
        }
        this.internalKey = key;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public Snapshot currentAccount(String username) {
        try {
            return client.get().uri(builder ->
                builder.path("/internal/identity/users/{username}").build(username))
                .header("X-Internal-Key", internalKey)
                .retrieve().body(Snapshot.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity sync unavailable");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity sync unavailable");
        }
    }

    public void revokeInCore(String authorization) {
        try {
            client.post().uri("/api/auth/logout").header("Authorization", authorization)
                .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Logout not available");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Logout not available");
        }
    }
}

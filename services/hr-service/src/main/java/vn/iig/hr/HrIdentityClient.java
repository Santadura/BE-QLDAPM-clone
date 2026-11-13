package vn.iig.hr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Live active role from private Identity contract; no JWT-only ACL. */
@Component
public class HrIdentityClient {
    public record Principal(String username,String role) {}
    private final RestClient client;
    private final String key;
    public HrIdentityClient(@Value("${iig.identity.url}") String url,
                            @Value("${iig.identity.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("HR Identity key required");
        this.key=secret;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public String currentRole(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        final Principal principal;
        try {
            principal=client.get().uri("/internal/identity/principal")
                .header("X-Internal-Key",key).header("Authorization",bearer)
                .retrieve().body(Principal.class);
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401 || ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Identity session invalid");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Identity authority unavailable");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Identity authority unavailable");
        }
        if(principal==null || principal.username()==null || principal.username().isBlank()
            || principal.role()==null || !principal.role().startsWith("ROLE_")
            || principal.role().length()<6)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Current Identity evidence incomplete");
        return principal.role().substring(5);
    }
}

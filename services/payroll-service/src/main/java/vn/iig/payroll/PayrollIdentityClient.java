package vn.iig.payroll;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Never accept stale JWT roles when reading/calculating salary. */
@Component
public class PayrollIdentityClient {
    public record Principal(String username,String role) {}
    private final RestClient client;
    private final String key;
    public PayrollIdentityClient(@Value("${iig.identity.url}") String url,
                                 @Value("${iig.identity.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Payroll Identity key required");
        key=secret;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public Principal currentPrincipal(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        final Principal user;
        try {
            user=client.get().uri("/internal/identity/principal")
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
        if(user==null || user.username()==null || user.username().isBlank()
            || user.role()==null || !user.role().startsWith("ROLE_")
            || user.role().length()<6)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Current Identity evidence incomplete");
        return user;
    }
}

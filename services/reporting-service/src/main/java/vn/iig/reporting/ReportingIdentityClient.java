package vn.iig.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Identity verifies live JWT/role before Reporting may return cached data. */
@Component
public class ReportingIdentityClient {
    private record Principal(String username,String role) {}
    private final RestClient client;
    private final String key;
    public ReportingIdentityClient(@Value("${iig.identity.url}") String url,
            @Value("${iig.identity.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Reporting requires a private Identity key");
        key=secret;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public ReportingCoreClient.Principal currentPrincipal(String bearer) {
        if(bearer==null || !bearer.startsWith("Bearer ") || bearer.length()<16)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        final Principal current;
        try {
            current=client.get().uri("/internal/identity/principal")
                .header("X-Internal-Key",key).header("Authorization",bearer)
                .retrieve().body(Principal.class);
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401 || ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Identity session invalid");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Identity live authority unavailable");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Identity live authority unavailable");
        }
        if(current==null || current.username()==null || current.username().isBlank()
            || current.role()==null || !current.role().startsWith("ROLE_")
            || current.role().length()<6)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Identity principal evidence incomplete");
        return new ReportingCoreClient.Principal(current.username(),current.role());
    }
}

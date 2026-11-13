package vn.iig.reporting;

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
 * REST boundary: Reporting may call Core APIs, but never its database.
 * Live authentication is checked before serving even a cached snapshot.
 */
@Component
public class ReportingCoreClient {
    private final RestClient client;
    private final String internalKey;
    private final ReportingIdentityClient identity;

    public ReportingCoreClient(@Value("${iig.core.url}") String coreUrl,
                               @Value("${iig.core.internal-key}") String internalKey,
                               ReportingIdentityClient identity) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalArgumentException("REPORTING_INTERNAL_API_KEY is required");
        }
        this.internalKey = internalKey;
        this.identity=identity;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client = RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public record Principal(String username, String role) {}

    public Principal currentPrincipal(String bearerToken) {
        return identity.currentPrincipal(bearerToken);
    }

    public void requireAdmin(String bearerToken) {
        if (!"ROLE_ADMIN".equals(currentPrincipal(bearerToken).role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
        }
    }

    public String currentDashboard(String username) {
        try {
            return client.get().uri(builder -> builder
                .path("/internal/reporting/dashboard")
                .queryParam("username", username).build())
                .header("X-Internal-Key", internalKey)
                .retrieve().body(String.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dashboard unavailable for account");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dashboard source unavailable");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dashboard source unavailable");
        }
    }

    public String currentStatistics(StatisticsQuery q) {
        try {
            return client.get().uri(builder -> {
                builder.path("/internal/reporting/statistics")
                       .queryParam("period", q.period())
                       .queryParam("year", q.year());
                if (q.month() != null) builder.queryParam("month", q.month());
                if (q.date() != null) builder.queryParam("date", q.date());
                return builder.build();
            }).header("X-Internal-Key", internalKey).retrieve().body(String.class);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Reporting source unavailable");
        }
    }
}

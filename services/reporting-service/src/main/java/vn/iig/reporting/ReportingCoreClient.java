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

    public ReportingCoreClient(@Value("${iig.core.url}") String coreUrl,
                               @Value("${iig.core.internal-key}") String internalKey) {
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalArgumentException("REPORTING_INTERNAL_API_KEY is required");
        }
        this.internalKey = internalKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client = RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public void requireAdmin(String bearerToken) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token required");
        }
        final Map<?,?> account;
        try {
            account = client.get().uri("/api/me")
                .header("Authorization", bearerToken)
                .retrieve().body(Map.class);
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity validation unavailable");
        }
        if (account == null || account.get("username") == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid session");
        }
        Object roles = account.get("authorities");
        if (!(roles instanceof List<?> authorities)
            || authorities.stream().noneMatch(item -> item instanceof Map<?,?> role
                && "ROLE_ADMIN".equals(role.get("authority")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
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

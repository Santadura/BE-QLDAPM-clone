package vn.iig.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

/**
 * A single, highest-priority browser CORS boundary for the public /api routes.
 *
 * Spring Cloud Gateway has its own handler-level CORS processing. Intercept
 * OPTIONS preflight before that routing layer so requests never accidentally
 * fall through to a downstream service or receive a 403 from an unmatched
 * gateway handler. Non-API routes and non-browser requests are not modified.
 */
@Configuration
public class GatewayCorsConfiguration {
    private static final List<String> METHODS =
        List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
    private static final Set<String> REQUEST_HEADERS =
        Set.of("authorization", "content-type", "accept", "x-requested-with");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter browserCorsWebFilter(
            @Value("${iig.cors.allowed-origins}") String origins) {
        Set<String> trustedOrigins = Set.copyOf(Arrays.stream(origins.split(","))
            .map(String::trim).filter(value -> !value.isBlank()).toList());
        if (trustedOrigins.isEmpty()) {
            throw new IllegalArgumentException("GATEWAY_ALLOWED_ORIGINS must not be empty");
        }
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            var response = exchange.getResponse();
            var requestHeaders = request.getHeaders();
            String origin = requestHeaders.getOrigin();
            if (!request.getPath().pathWithinApplication().value().startsWith("/api/")
                    || origin == null) {
                return chain.filter(exchange);
            }

            var headers = response.getHeaders();
            headers.setVary(List.of("Origin", "Access-Control-Request-Method",
                "Access-Control-Request-Headers"));
            boolean preflight = HttpMethod.OPTIONS.equals(request.getMethod())
                && requestHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
            boolean trusted = trustedOrigins.contains(origin);

            if (preflight) {
                String method = requestHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
                String requestedHeaders = requestHeaders.getFirst(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
                boolean headersAllowed = requestedHeaders == null ||
                    Arrays.stream(requestedHeaders.split(",")).map(String::trim)
                        .map(String::toLowerCase)
                        .allMatch(REQUEST_HEADERS::contains);
                if (!trusted || !METHODS.contains(method) || !headersAllowed) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return response.setComplete();
                }
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", METHODS));
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    "Authorization, Content-Type, Accept, X-Requested-With");
                headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
                response.setStatusCode(HttpStatus.OK);
                return response.setComplete();
            }

            // Non-preflight request must still obey backend security and JWT.
            // The gateway only writes browser CORS headers; it never authenticates.
            if (trusted) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
            return chain.filter(exchange);
        };
    }
}

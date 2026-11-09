package vn.iig.gateway;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** Gateway owns browser preflight; the legacy core keeps its own CORS for rollback. */
@Configuration
public class GatewayCorsConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CorsWebFilter browserCorsWebFilter(
            @Value("${iig.cors.allowed-origins}") String origins) {
        List<String> allowedOrigins = Arrays.stream(origins.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("GATEWAY_ALLOWED_ORIGINS must not be empty");
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}

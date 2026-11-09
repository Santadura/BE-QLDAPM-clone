package com.example.demo.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Transactional-outbox-like relay over the existing committed audit_logs table.
 * The domain transaction commits its audit row before the relay can see it.
 * An acknowledgement is recorded only after the independent audit service
 * accepts an idempotent event. A crash or network failure means safe retry.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "iig.audit.relay-enabled", havingValue = "true")
public class AuditOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);
    private final JdbcTemplate db;
    private final RestClient client;

    private record Event(String id, String username, String action,
                         String type, String entityId, String description, Instant time) {}

    public AuditOutboxRelay(JdbcTemplate db,
            @Value("${iig.audit.base-url}") String baseUrl,
            @Value("${iig.audit.internal-key}") String internalKey) {
        if (baseUrl.isBlank() || internalKey.isBlank()) {
            throw new IllegalArgumentException("AUDIT_SERVICE_URL and AUDIT_INTERNAL_API_KEY are required");
        }
        this.db = db;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Internal-Key", internalKey)
            .requestFactory(factory)
            .build();
    }

    @Scheduled(initialDelayString = "${iig.audit.initial-delay-ms:8000}",
               fixedDelayString = "${iig.audit.poll-ms:5000}")
    public void deliver() {
        List<Event> batch = db.query(
            "SELECT a.audit_log_id, a.username, a.action, a.entity_type, " +
            "a.entity_id, a.description, a.created_at " +
            "FROM audit_logs a LEFT JOIN audit_outbox_deliveries d ON d.audit_log_id=a.audit_log_id " +
            "WHERE d.audit_log_id IS NULL ORDER BY a.created_at, a.audit_log_id LIMIT 100",
            (rs, i) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getTimestamp(7).toInstant()));

        for (Event event : batch) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("eventId", event.id());
            body.put("username", event.username());
            body.put("action", event.action());
            body.put("entityType", event.type());
            body.put("entityId", event.entityId());
            body.put("description", event.description());
            body.put("occurredAt", event.time().toString());
            try {
                client.post().uri("/internal/audit/events").body(body)
                    .retrieve().toBodilessEntity();
                db.update("INSERT INTO audit_outbox_deliveries(audit_log_id) " +
                          "VALUES (?) ON CONFLICT DO NOTHING", event.id());
            } catch (RestClientException ex) {
                // Do not mark as delivered: the next poll will retry.
                log.warn("Audit service unavailable, keeping event {} pending: {}",
                    event.id(), ex.getMessage());
                break;
            }
        }
    }
}

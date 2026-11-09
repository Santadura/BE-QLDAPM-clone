package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.example.demo.controller.SchedulingAssignmentReadBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Relay the committed Core assignment outbox into Scheduling's read-only
 * projection, with at-least-once semantics and exact-version acknowledgement.
 */
@Component
@ConditionalOnProperty(name="iig.scheduling.assignment-relay-enabled",havingValue="true")
public class SchedulingAssignmentOutboxRelay {
    private static final Logger log=LoggerFactory.getLogger(SchedulingAssignmentOutboxRelay.class);
    private final RestClient client;
    private final String key;
    private final SchedulingAssignmentReadBridge source;

    public SchedulingAssignmentOutboxRelay(SchedulingAssignmentReadBridge source,
            @Value("${iig.scheduling.url}") String url,
            @Value("${iig.scheduling.internal-key}") String key) {
        if(key==null || key.isBlank()) throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.source=source;
        this.key=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(5000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Scheduled(initialDelay=15000,fixedDelay=4000)
    public void publish() {
        try {
            List<SchedulingAssignmentReadBridge.AssignmentSnapshot> changed=source.changes(key,100);
            for(var item:changed) {
                try {
                    client.post().uri("/internal/scheduling/assignments/project")
                        .header("X-Internal-Key",key).body(item)
                        .retrieve().toBodilessEntity();
                    source.acknowledge(key,new SchedulingAssignmentReadBridge.Acknowledgement(
                        item.sourceType(),item.id(),item.version()));
                } catch(Exception ex) {
                    log.warn("Scheduling read-model relay deferred for {}:{} v{}: {}",
                        item.sourceType(),item.id(),item.version(),ex.getMessage());
                    return;
                }
            }
        } catch(Exception ex) {
            log.warn("Scheduling assignment outbox source unavailable: {}",ex.getMessage());
        }
    }
}

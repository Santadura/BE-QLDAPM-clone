package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live authoritative availability check + temporary reservation. The
 * reservation persists in Scheduling DB until Core's assignment transaction
 * completes, so an availability edit cannot race with an uncommitted
 * teacher/CS/staff assignment. Failed releases expire server-side.
 */
@Component
public class SchedulingReservationClient {
    private static final Logger log=LoggerFactory.getLogger(SchedulingReservationClient.class);
    public record Query(String employeeId,LocalDate date,LocalTime startTime,
                        LocalTime endTime,String campus) {}
    public record Lease(boolean approved,String reservationId) {}

    private final RestClient client;
    private final boolean enabled;
    private final String key;

    public SchedulingReservationClient(
            @Value("${iig.scheduling.external-availability:false}") boolean enabled,
            @Value("${iig.scheduling.url:http://localhost:8098}") String baseUrl,
            @Value("${iig.scheduling.internal-key:}") String key) {
        this.enabled=enabled;
        if(enabled && (key==null || key.isBlank()))
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        this.key=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        this.client=RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean enabled() { return enabled; }

    public boolean reserveIfApproved(String employeeId,LocalDate date,LocalTime from,
                                     LocalTime to,String campus) {
        if(!enabled) throw new IllegalStateException("Scheduling bridge not enabled");
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Assignment requires an active Core DB transaction");
        try {
            Lease lease=client.post().uri("/internal/scheduling/reservations")
                .header("X-Internal-Key",key)
                .body(new Query(employeeId,date,from,to,campus))
                .retrieve().body(Lease.class);
            if(lease==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Empty Scheduling reservation reply");
            if(!lease.approved()) return false;
            if(lease.reservationId()==null || lease.reservationId().isBlank())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Scheduling did not return a reservation ID");
            final String id=lease.reservationId();
            // Must release AFTER commit/rollback, not on method return.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        client.delete().uri("/internal/scheduling/reservations/{id}",id)
                            .header("X-Internal-Key",key).retrieve().toBodilessEntity();
                    } catch(Exception ex) {
                        // Scheduled expiration prevents a permanent block on worker edits.
                        log.warn("Deferred Scheduling reservation {} release failed",id,ex);
                    }
                }
            });
            return true;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Scheduling availability validation unavailable",ex);
        }
    }
}

package vn.iig.attendance;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Private Core attendance outbox client; never accesses Core PostgreSQL. */
@Component
public class AttendanceSourceClient {
    public record Event(long eventId,String teachingScheduleId,String eventKind,
                        String payloadJson,String payloadHash) {}
    public record Ack(long eventId,String payloadHash) {}
    public record AckRequest(List<Ack> events) {}
    public record AckReceipt(int confirmed) {}
    private final RestClient client;
    private final String secret;

    public AttendanceSourceClient(
            @Value("${iig.core.url}") String coreUrl,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        this.secret=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        this.client=RestClient.builder().baseUrl(coreUrl)
            .requestFactory(factory).build();
    }

    public List<Event> pending(int limit) {
        try {
            Event[] events=client.get()
                .uri(uri->uri.path("/internal/attendance/events")
                    .queryParam("limit",limit).build())
                .header("X-Internal-Key",secret)
                .retrieve().body(Event[].class);
            if(events==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core Attendance outbox response missing");
            return Arrays.asList(events);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Attendance source feed unavailable",ex);
        }
    }

    public void acknowledge(Event event) {
        try {
            AckReceipt reply=client.post()
                .uri("/internal/attendance/events/ack")
                .header("X-Internal-Key",secret)
                .body(new AckRequest(List.of(new Ack(
                    event.eventId(),event.payloadHash()))))
                .retrieve().body(AckReceipt.class);
            if(reply==null || reply.confirmed()!=1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Attendance source event was not acknowledged");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance source event acknowledgement unavailable",ex);
        }
    }
}

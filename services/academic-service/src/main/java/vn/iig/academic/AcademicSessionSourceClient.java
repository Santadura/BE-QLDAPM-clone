package vn.iig.academic;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Key-protected Core source feed; NO cross-database access. */
@Component
public class AcademicSessionSourceClient {
    public record Event(long eventId,String classSessionId,String classId,
        String eventKind,long sourceRevision,String payloadJson,String payloadHash) {}
    public record Ack(long eventId,String payloadHash) {}
    public record Summary(long sourceRows,long eventCount,long pendingEvents,long maxEventId) {}
    private final RestClient client;
    private final String secret;

    public AcademicSessionSourceClient(@Value("${iig.core.url}") String url,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Academic internal key required");
        this.secret=secret;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    private List<Event> get(long after,int limit,boolean history) {
        try {
            String path="/internal/academic/sessions/events"+(history?"/history":"");
            Event[] rows=client.get().uri(uri->{
                var b=uri.path(path).queryParam("limit",limit);
                if(history)b.queryParam("after",after);
                return b.build();
            }).header("X-Internal-Key",secret).retrieve().body(Event[].class);
            if(rows==null || rows.length>limit)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Academic source event batch");
            return Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic source events unavailable",ex);
        }
    }
    public List<Event> pending(int limit) {return get(0,limit,false);}
    public List<Event> history(long cursor,int limit) {return get(cursor,limit,true);}

    public Summary summary() {
        try {
            Summary summary=client.get().uri("/internal/academic/sessions/events/summary")
                .header("X-Internal-Key",secret).retrieve().body(Summary.class);
            if(summary==null || summary.sourceRows()<0 || summary.eventCount()<0 ||
                summary.pendingEvents()<0 || summary.maxEventId()<0)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Core Academic source summary");
            return summary;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Academic source summary unavailable",ex);
        }
    }

    public void acknowledge(Event event) {
        try {
            Ack response=client.post().uri("/internal/academic/sessions/events/ack")
                .header("X-Internal-Key",secret)
                .body(new Ack(event.eventId(),event.payloadHash()))
                .retrieve().body(Ack.class);
            if(response==null || response.eventId()!=event.eventId() ||
                !event.payloadHash().equals(response.payloadHash()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core Academic acknowledgement identity mismatch");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Academic event acknowledgement unavailable",ex);
        }
    }
}

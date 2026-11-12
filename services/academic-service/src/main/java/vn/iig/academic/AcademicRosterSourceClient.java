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

/**
 * Core student/roster history over private HTTP. Never uses Core's SQL
 * credentials or a shared database transaction.
 */
@Component
public class AcademicRosterSourceClient {
    public record Event(long eventId,String entityType,String entityId,
        String eventKind,long sourceRevision,String payloadJson,String payloadHash) {}
    public record Summary(long sourceEvents,long lastEventId,long studentRows,
        long membershipRows,long distinctEntities) {}

    private final RestClient core;
    private final String key;
    public AcademicRosterSourceClient(@Value("${iig.core.url}") String coreUrl,
            @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("Academic roster source key required");
        key=internalKey;
        SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2500); f.setReadTimeout(8000);
        core=RestClient.builder().baseUrl(coreUrl).requestFactory(f).build();
    }

    public List<Event> history(long after,int limit) {
        if(after<0 || limit<1 || limit>100)
            throw new IllegalArgumentException("Academic roster history page out of bounds");
        try {
            Event[] rows=core.get().uri(b->b
                .path("/internal/academic/student-roster/events/history")
                .queryParam("after",after).queryParam("limit",limit).build())
                .header("X-Internal-Key",key)
                .retrieve().body(Event[].class);
            if(rows==null || rows.length>limit)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core roster source returned an invalid page");
            return Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core academic student roster source unavailable",ex);
        }
    }

    public Summary summary() {
        try {
            Summary result=core.get()
                .uri("/internal/academic/student-roster/events/summary")
                .header("X-Internal-Key",key).retrieve().body(Summary.class);
            if(result==null || result.sourceEvents()<0 || result.lastEventId()<0 ||
                result.studentRows()<0 || result.membershipRows()<0 ||
                result.distinctEntities()<0)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Incomplete Core student roster source summary");
            return result;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core student roster source summary unavailable",ex);
        }
    }
}

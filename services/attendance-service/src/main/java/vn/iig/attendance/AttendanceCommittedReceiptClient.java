package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * READ ONLY across the network: never submit a business PUT while repairing
 * an uncertain Attendance inbox. A Core 404 means no durable proof, not
 * permission to retry or discard the command.
 */
@Component
public class AttendanceCommittedReceiptClient {
    public record Query(String actorUsername,String classId,
        String teachingScheduleId,String requestKey,String requestHash) {}
    public record Proof(String actorUsername,String classId,
        String teachingScheduleId,String requestKey,String requestHash,
        JsonNode response) {}

    private final RestClient core;
    private final String internalKey;
    public AttendanceCommittedReceiptClient(
            @Value("${iig.core.url}") String coreUrl,
            @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("Attendance recovery internal key required");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(8000);
        this.core=RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
        this.internalKey=internalKey;
    }

    // Package-private constructor allows testing the exact HTTP contract.
    AttendanceCommittedReceiptClient(RestClient core,String internalKey) {
        if(core==null || internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("Exact Core client and key required");
        this.core=core;this.internalKey=internalKey;
    }

    public JsonNode confirmed(AttendanceCommandInbox.Pending item) {
        if(item==null || item.actor()==null || item.classId()==null ||
            item.scheduleId()==null || item.requestKey()==null ||
            item.requestHash()==null ||
            !item.requestHash().matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Missing immutable Attendance inbox request identity");
        Query query=new Query(item.actor(),item.classId(),item.scheduleId(),
            item.requestKey(),item.requestHash());
        final Proof response;
        try {
            response=core.post().uri("/internal/attendance/commands/receipt")
                .header("X-Internal-Key",internalKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(query).retrieve().body(Proof.class);
        } catch(HttpClientErrorException.NotFound absent) {
            // No committed Core command. NEVER complete the local inbox.
            return null;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Exact committed Core Attendance receipt unavailable",ex);
        }
        if(response==null || !item.actor().equals(response.actorUsername()) ||
            !item.classId().equals(response.classId()) ||
            !item.scheduleId().equals(response.teachingScheduleId()) ||
            !item.requestKey().equals(response.requestKey()) ||
            !item.requestHash().equals(response.requestHash()) ||
            response.response()==null || !response.response().isObject() ||
            !item.scheduleId().equals(response.response()
                .path("teachingScheduleId").asText()) ||
            !Set.of("PRESENT","ABSENT","LATE").contains(
                response.response().path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Committed Core attendance receipt differs from original inbox command");
        return response.response();
    }
}

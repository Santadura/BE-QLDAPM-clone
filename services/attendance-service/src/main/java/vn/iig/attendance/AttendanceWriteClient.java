package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The sole microservices public Attendance write entry delegates to Core's
 * original locked transaction. No cross-service JDBC and no blind retries.
 */
@Component
public class AttendanceWriteClient {
    private final RestClient client;
    private final String secret;

    public AttendanceWriteClient(
            @Value("${iig.core.url}") String coreUrl,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        secret=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(coreUrl).requestFactory(factory).build();
    }

    public JsonNode mark(String classId,String scheduleId,String bearer,String status) {
        try {
            JsonNode result=client.put()
                .uri("/internal/attendance/commands/classes/{classId}/teacher-attendance/{id}",
                    classId,scheduleId)
                .header("X-Internal-Key",secret)
                .header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MarkRequest(status))
                .retrieve().body(JsonNode.class);
            if(result==null || !result.isObject())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid Core attendance command response");
            return result;
        } catch(RestClientResponseException ex) {
            HttpStatus upstreamStatus=HttpStatus.resolve(ex.getStatusCode().value());
            if(upstreamStatus==HttpStatus.BAD_REQUEST || upstreamStatus==HttpStatus.UNAUTHORIZED ||
                    upstreamStatus==HttpStatus.FORBIDDEN || upstreamStatus==HttpStatus.NOT_FOUND ||
                    upstreamStatus==HttpStatus.CONFLICT)
                throw new ResponseStatusException(upstreamStatus,
                    "Core rejected teacher attendance command",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core attendance writer unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core attendance writer unavailable",ex);
        }
    }
    public record MarkRequest(String status) {}
}

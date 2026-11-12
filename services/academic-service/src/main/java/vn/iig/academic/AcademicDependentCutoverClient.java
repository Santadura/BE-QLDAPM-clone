package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Independent, key-protected preflight of Attendance and Scheduling.  Neither
 * service may read another service's PostgreSQL.  This client can NEVER
 * toggle writer modes or authorize a production route by itself.
 */
@Component
public class AcademicDependentCutoverClient {
    public record Proof(JsonNode attendance,JsonNode scheduling) {}
    private final RestClient attendance;
    private final RestClient scheduling;
    private final String attendanceKey;
    private final String schedulingKey;

    private static RestClient client(String url) {
        if(url==null || !url.matches("https?://[^\\s]+"))
            throw new IllegalArgumentException("Private cutover target URL is required");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(12000);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public AcademicDependentCutoverClient(
            @Value("${iig.crossdomain.attendance-url}") String attendanceUrl,
            @Value("${iig.crossdomain.attendance-key}") String attendanceKey,
            @Value("${iig.crossdomain.scheduling-url}") String schedulingUrl,
            @Value("${iig.crossdomain.scheduling-key}") String schedulingKey) {
        this(client(attendanceUrl),attendanceKey,
            client(schedulingUrl),schedulingKey);
    }

    // Test seam for signed, private cross-service HTTP contract assertions.
    AcademicDependentCutoverClient(RestClient attendance,String attendanceKey,
            RestClient scheduling,String schedulingKey) {
        if(attendance==null || scheduling==null ||
                attendanceKey==null || attendanceKey.isBlank() ||
                schedulingKey==null || schedulingKey.isBlank())
            throw new IllegalArgumentException("Independent cutover clients and keys required");
        this.attendance=attendance;
        this.scheduling=scheduling;
        this.attendanceKey=attendanceKey;
        this.schedulingKey=schedulingKey;
    }

    private JsonNode report(RestClient remote,String key,String uri,String domain) {
        final JsonNode proof;
        try {
            proof=remote.get().uri(uri).header("X-Internal-Key",key)
                .retrieve().body(JsonNode.class);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                domain+" independent verification unavailable",ex);
        }
        if(proof==null || !proof.isObject() ||
                !proof.path("ready").isBoolean() || !proof.path("ready").asBoolean() ||
                !proof.path("state").isTextual() ||
                !"READY".equals(proof.path("state").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                domain+" independently reported cutover not ready");
        return proof;
    }

    public Proof verify() {
        JsonNode a=report(attendance,attendanceKey,
            "/internal/attendance/verify/frozen","Attendance");
        JsonNode s=report(scheduling,schedulingKey,
            "/internal/scheduling/assignments/verify","Scheduling");
        if(!a.path("sourceRows").isIntegralNumber() ||
            !a.path("verifiedRows").isIntegralNumber() ||
            a.path("sourceRows").asLong()<0 ||
            a.path("sourceRows").asLong()!=a.path("verifiedRows").asLong() ||
            !a.path("mismatchCount").isIntegralNumber() ||
            a.path("mismatchCount").asLong()!=0 ||
            !s.path("sourceRows").isIntegralNumber() ||
            !s.path("localRows").isIntegralNumber() ||
            !s.path("writeShadowRows").isIntegralNumber() ||
            !s.path("checkedRows").isIntegralNumber() ||
            s.path("sourceRows").asLong()<0 ||
            s.path("sourceRows").asLong()!=s.path("localRows").asLong() ||
            s.path("sourceRows").asLong()!=s.path("writeShadowRows").asLong() ||
            s.path("sourceRows").asLong()!=s.path("checkedRows").asLong() ||
            !s.path("mismatches").isIntegralNumber() ||
            s.path("mismatches").asLong()!=0 ||
            !s.path("pendingChanges").isIntegralNumber() ||
            s.path("pendingChanges").asLong()!=0 ||
            !s.path("unresolvedCommands").isIntegralNumber() ||
            s.path("unresolvedCommands").asLong()!=0 ||
            !s.path("liveSchedules").isIntegralNumber() ||
            !s.path("trackedLiveSchedules").isIntegralNumber() ||
            s.path("liveSchedules").asLong()!=s.path("trackedLiveSchedules").asLong())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Independent Attendance/Scheduling cutover counts diverged");
        return new Proof(a.deepCopy(),s.deepCopy());
    }
}

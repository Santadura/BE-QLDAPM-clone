package vn.iig.academic;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Private HTTP only; Core remains sole transactional timetable authority. */
@Component
public class AcademicWriteClient {
    private final RestClient client;
    private final String secret;
    public AcademicWriteClient(@Value("${iig.core.url}") String url,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())throw new IllegalArgumentException("Academic key required");
        secret=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public JsonNode command(String operation,String classId,JsonNode body,
            String bearer,String key) {
        String url="/internal/academic/commands/classes/{id}/"+
            ("PLAN".equals(operation)?"planned-sessions":"reschedule");
        try {
            RestClient.RequestBodySpec request="PLAN".equals(operation)
                ?client.put().uri(url,classId):client.post().uri(url,classId);
            request.header("X-Internal-Key",secret).header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON);
            if(key!=null)request.header("Idempotency-Key",key);
            JsonNode result=request.body(body).retrieve().body(JsonNode.class);
            if(result==null || !result.isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Core returned invalid session response");
            return result;
        } catch(HttpClientErrorException ex) {
            HttpStatus status=HttpStatus.resolve(ex.getStatusCode().value());
            if(status==HttpStatus.BAD_REQUEST || status==HttpStatus.UNAUTHORIZED ||
                status==HttpStatus.FORBIDDEN || status==HttpStatus.NOT_FOUND ||
                status==HttpStatus.CONFLICT)
                throw new ResponseStatusException(status,"Core rejected Academic command",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Academic command unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Academic command unavailable",ex);
        }
    }
}

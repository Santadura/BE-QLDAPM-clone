package vn.iig.attendance;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional Attendance read source: no cross-service database access.
 * Core remains the sole teacher attendance writer and enforces the exact
 * class access scope/CS support shift on its existing authenticated GET.
 */
@Component
public class AttendanceCoreClient {
    public record Principal(String username,String role) {}
    private final RestClient client;

    public AttendanceCoreClient(@Value("${iig.core.url}") String coreUrl) {
        SimpleClientHttpRequestFactory http=new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(2500);
        http.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(coreUrl).requestFactory(http).build();
    }

    public Principal currentPrincipal(String token) {
        if(token==null || !token.startsWith("Bearer ") || token.length()<15)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Bearer token required");
        final Map<?,?> account;
        try {
            account=client.get().uri("/api/me")
                .header("Authorization",token)
                .retrieve().body(Map.class);
        } catch(HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Invalid live Core session");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core session verification unavailable",ex);
        }
        if(account==null || !(account.get("username") instanceof String username)
                || username.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Core identity");
        if(!(account.get("authorities") instanceof List<?> authorities))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Role information missing");
        String role=authorities.stream().filter(Map.class::isInstance)
            .map(Map.class::cast).map(a->String.valueOf(a.get("authority")))
            .filter(s->s.startsWith("ROLE_")).findFirst().orElse("");
        return new Principal(username,role);
    }

    public String teacherAttendance(String classId,String bearer) {
        try {
            String result=client.get().uri(builder->builder
                .path("/api/classes/{classId}/teacher-attendance").build(classId))
                .header("Authorization",bearer).retrieve().body(String.class);
            if(result==null || result.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Empty Core Attendance response");
            return result;
        } catch(HttpClientErrorException ex) {
            HttpStatus status=HttpStatus.resolve(ex.getStatusCode().value());
            if(status==HttpStatus.UNAUTHORIZED)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
            if(status==HttpStatus.FORBIDDEN)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "CS has no access to this class");
            if(status==HttpStatus.NOT_FOUND)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Attendance source class unavailable");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance source rejected request",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Attendance source unavailable",ex);
        }
    }
}

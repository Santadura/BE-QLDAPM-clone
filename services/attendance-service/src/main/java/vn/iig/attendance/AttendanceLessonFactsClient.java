package vn.iig.attendance;

import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Core-owned versioned facts are read by private HTTP, not Core JDBC. */
@Component
public class AttendanceLessonFactsClient {
    public record Facts(String classId,String teachingScheduleId,String sessionId,
        long classVersion,long sessionVersion,long teacherVersion,
        String supportScheduleId,long supportVersion,String actorEmployeeId,
        LocalDate date,LocalTime startTime,LocalTime endTime,
        long attendanceGeneration,long schedulingGeneration,String token) {}

    private final RestClient client;
    private final String secret;

    public AttendanceLessonFactsClient(
            @Value("${iig.core.url}") String coreUrl,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("ATTENDANCE_INTERNAL_API_KEY required");
        secret=key;
        SimpleClientHttpRequestFactory http=new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(2500);
        http.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(coreUrl).requestFactory(http).build();
    }

    public String currentToken(String classId,String scheduleId,String bearer) {
        try {
            Facts f=client.get().uri(uri->uri
                .path("/internal/attendance/lesson-facts")
                .queryParam("classId",classId)
                .queryParam("teachingScheduleId",scheduleId).build())
                .header("X-Internal-Key",secret)
                .header("Authorization",bearer)
                .retrieve().body(Facts.class);
            if(f==null || !classId.equals(f.classId()) ||
                    !scheduleId.equals(f.teachingScheduleId()) ||
                    f.sessionId()==null || f.sessionId().isBlank() ||
                    f.supportScheduleId()==null || f.supportScheduleId().isBlank() ||
                    f.actorEmployeeId()==null || f.actorEmployeeId().isBlank() ||
                    f.classVersion()<1 || f.sessionVersion()<1 ||
                    f.teacherVersion()<1 || f.supportVersion()<1 ||
                    f.attendanceGeneration()<1 || f.schedulingGeneration()<1 ||
                    f.token()==null || !f.token().matches("[0-9a-f]{64}"))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid Core Attendance lesson-facts response");
            return f.token();
        } catch(RestClientResponseException ex) {
            HttpStatus status=HttpStatus.resolve(ex.getStatusCode().value());
            if(status==HttpStatus.UNAUTHORIZED || status==HttpStatus.FORBIDDEN ||
                    status==HttpStatus.BAD_REQUEST || status==HttpStatus.NOT_FOUND ||
                    status==HttpStatus.CONFLICT)
                throw new ResponseStatusException(status,
                    "Core rejected Attendance version preflight",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance Academic/CS facts unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance Academic/CS facts unavailable",ex);
        }
    }
}

package vn.iig.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The Core PostgreSQL is NEVER queried by Attendance. This independent
 * service only invokes private, keyed HTTP contracts with a live CS bearer
 * during admission. Event projection later needs the immutable Core claim,
 * not a stored/replayed user JWT.
 */
@Component
public class AttendanceNativeReferenceClient {
    public record Facts(String classId,String teachingScheduleId,String sessionId,
        long classVersion,long sessionVersion,long teacherVersion,
        String supportScheduleId,long supportVersion,String actorEmployeeId,
        java.time.LocalDate date,java.time.LocalTime startTime,
        java.time.LocalTime endTime,long attendanceGeneration,
        long schedulingGeneration,String token) {}
    public record Claim(String coreClaimId,String actorUsername,
        String actorEmployeeId,String classId,String teachingScheduleId,
        String requestKey,String requestHash,String status,String factsToken,
        long writerGeneration,String previousAttendanceId,
        String previousStatus,String ownerMode,String state) {}
    public record Command(String classId,String teachingScheduleId,
        String requestKey,String requestHash,String status,String factsToken) {}
    public record ReferenceRequest(String payloadCanonical,String payloadHash) {}
    public record Receipt(String eventId,String teachingScheduleId,long version,
        String payloadHash,String outcome,JsonNode response) {}

    private final RestClient core;
    private final String key;

    @Autowired
    public AttendanceNativeReferenceClient(
        @Value("${iig.core.url}") String coreUrl,
        @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Native Core reference key required");
        var f=new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2500);f.setReadTimeout(10000);
        this.core=RestClient.builder().baseUrl(coreUrl).requestFactory(f).build();
        this.key=key;
    }

    AttendanceNativeReferenceClient(RestClient core,String key) {
        if(core==null || key==null || key.isBlank())
            throw new IllegalArgumentException("Exact private Core client required");
        this.core=core;this.key=key;
    }

    private static ResponseStatusException invalid(RestClientResponseException ex,
            String operation) {
        if(ex.getStatusCode().value()==401 || ex.getStatusCode().value()==403 ||
            ex.getStatusCode().value()==404 || ex.getStatusCode().value()==409)
            return new ResponseStatusException(ex.getStatusCode(),
                "Core rejected native Attendance "+operation,ex);
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Native Attendance Core "+operation+" unavailable",ex);
    }

    public Facts facts(String classId,String scheduleId,String bearer) {
        try {
            var f=core.get().uri(uri->uri
                .path("/internal/attendance/lesson-facts/native")
                .queryParam("classId",classId)
                .queryParam("teachingScheduleId",scheduleId).build())
                .header("X-Internal-Key",key)
                .header("Authorization",bearer)
                .retrieve().body(Facts.class);
            if(f==null || !classId.equals(f.classId()) ||
                !scheduleId.equals(f.teachingScheduleId()) ||
                f.token()==null || !f.token().matches("[0-9a-f]{64}") ||
                f.attendanceGeneration()<2 || f.schedulingGeneration()<1 ||
                f.actorEmployeeId()==null || f.actorEmployeeId().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid native Academic/CS fact proof");
            return f;
        } catch(RestClientResponseException ex) {throw invalid(ex,"facts");}
        catch(RestClientException ex) {throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Core native facts unavailable",ex);}
    }

    public Claim claim(String classId,String scheduleId,String bearer,
        String keyId,String requestHash,String status,String factToken,
        String expectedActor) {
        try {
            var c=core.post().uri("/internal/attendance/native/claims")
                .header("X-Internal-Key",key)
                .header("Authorization",bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Command(classId,scheduleId,keyId,requestHash,status,factToken))
                .retrieve().body(Claim.class);
            if(c==null || !expectedActor.equals(c.actorUsername()) ||
                !classId.equals(c.classId()) ||
                !scheduleId.equals(c.teachingScheduleId()) ||
                !keyId.equals(c.requestKey()) ||
                !requestHash.equals(c.requestHash()) ||
                !status.equals(c.status()) ||
                !factToken.equals(c.factsToken()) ||
                c.writerGeneration()<2 ||
                c.actorEmployeeId()==null || c.actorEmployeeId().isBlank() ||
                !"ATTENDANCE".equals(c.ownerMode()) ||
                !java.util.Set.of("OPEN","CONFIRMED").contains(c.state()) ||
                c.coreClaimId()==null ||
                !c.coreClaimId().matches("[0-9a-fA-F-]{36}"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core native writer claim differs from the actor's exact intent");
            return c;
        } catch(RestClientResponseException ex) {throw invalid(ex,"claim");}
        catch(RestClientException ex) {throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Core native claim unavailable",ex);}
    }

    /**
     * Idempotent exact projection. Timeout may occur AFTER a Core COMMIT;
     * retry only this SAME immutable event, never mutate the source again.
     */
    public Receipt reference(AttendanceNativeLedgerRepository.Pending original) {
        if(original==null ||
            original.payloadCanonical()==null || original.payloadHash()==null ||
            !AttendanceNativeLedgerRepository.digest(
                original.payloadCanonical()).equals(original.payloadHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot send a divergent native Attendance event");
        final Receipt r;
        try {
            r=core.post().uri("/internal/attendance/native/reference")
                .header("X-Internal-Key",key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ReferenceRequest(
                    original.payloadCanonical(),original.payloadHash()))
                .retrieve().body(Receipt.class);
        } catch(RestClientResponseException ex) {throw invalid(ex,"reference");}
        catch(RestClientException ex) {throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,"Core native receipt unavailable",ex);}
        if(r==null || !"COMMITTED".equals(r.outcome()) ||
            !original.eventId().equals(r.eventId()) ||
            !original.teachingScheduleId().equals(r.teachingScheduleId()) ||
            original.version()!=r.version() ||
            !original.payloadHash().equals(r.payloadHash()) ||
            r.response()==null || !r.response().isObject() ||
            !original.teachingScheduleId().equals(
                r.response().path("teachingScheduleId").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core native reference did not prove exact commit");
        return r;
    }
}

package vn.iig.payroll;

import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read ONLY from Attendance's independent, normalized PostgreSQL ledger.
 * Never touch Core database. A verified snapshot is evidence for an
 * operator's cutover review, NOT a Payroll source cutover by itself.
 */
@Component
public class PayrollAttendanceEvidenceClient {
    public record Evidence(String teachingScheduleId,String eventKind,String status,
                           long sourceEventId,String sourceHash) {}
    public record Snapshot(long sourceRevision,long sourceEventCount,
                           long attendanceGeneration,long schedulingGeneration,
                           List<Evidence> evidence) {}

    private final RestClient client;
    private final String key;

    public PayrollAttendanceEvidenceClient(
            @Value("${iig.attendance.url}") String attendanceUrl,
            @Value("${iig.core.internal-key}") String payrollKey) {
        if(payrollKey==null || payrollKey.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        this.key=payrollKey;
        SimpleClientHttpRequestFactory http=new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(2500);
        http.setReadTimeout(20000);
        client=RestClient.builder().baseUrl(attendanceUrl)
            .requestFactory(http).build();
    }

    public Snapshot snapshot(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100 ||
                ids.stream().anyMatch(id->id==null ||
                    !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}")) ||
                new HashSet<>(ids).size()!=ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid Payroll Attendance evidence identities");
        try {
            Snapshot value=client.get()
                .uri(uri->uri.path("/internal/attendance/payroll/evidence")
                    .queryParam("ids",String.join(",",ids)).build())
                .header("X-Internal-Key",key)
                .retrieve().body(Snapshot.class);
            if(value==null || value.evidence()==null ||
                    value.evidence().size()>ids.size() ||
                    value.sourceRevision()<0 || value.sourceEventCount()<0 ||
                    value.attendanceGeneration()<1 || value.schedulingGeneration()<1)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Invalid Attendance Payroll evidence response");
            Set<String> requested=new HashSet<>(ids);
            Set<String> seen=new HashSet<>();
            for(Evidence proof:value.evidence()) {
                if(proof==null || proof.teachingScheduleId()==null ||
                        !requested.contains(proof.teachingScheduleId()) ||
                        !seen.add(proof.teachingScheduleId()) ||
                        !Set.of("UPSERT","DELETE").contains(proof.eventKind()) ||
                        proof.sourceEventId()<1 || proof.sourceHash()==null ||
                        !proof.sourceHash().matches("[0-9a-f]{64}") ||
                        (!"DELETE".equals(proof.eventKind()) &&
                            !Set.of("PRESENT","ABSENT","LATE").contains(proof.status())))
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Malformed independent Attendance source evidence");
            }
            return value;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Independent Attendance evidence unavailable",ex);
        }
    }
}

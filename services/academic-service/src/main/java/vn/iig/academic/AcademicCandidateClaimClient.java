package vn.iig.academic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Core durable claim barrier. Acquire before local commit; release after. */
@Component
public class AcademicCandidateClaimClient {
    public record Claim(String actor,String requestKey,String requestHash,
        String classId,String sessionId,long sourceEventId,long sourceRevision,
        long academicGeneration,long attendanceGeneration,long schedulingGeneration) {}
    public record Release(Claim claim,String candidateCommandId,String outboxHash) {}
    public record Result(String state,String actor,String requestKey) {}

    private final RestClient core;
    private final String key;
    public AcademicCandidateClaimClient(@Value("${iig.core.url}") String url,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Academic candidate internal key required");
        this.key=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(8000);
        core=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    private Result call(String suffix,Object input) {
        try {
            var r=core.post().uri("/internal/academic/candidate-claims"+suffix)
                .header("X-Internal-Key",key).body(input).retrieve().body(Result.class);
            if(r==null || r.actor()==null || r.requestKey()==null || r.state()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Incomplete Core candidate claim receipt");
            return r;
        } catch(RestClientResponseException ex) {
            if(ex.getStatusCode().value()==409)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core candidate claim has conflicting evidence",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core candidate claim refused the private request",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core candidate claim unavailable",ex);
        }
    }
    public Result acquire(Claim claim) {
        Result r=call("",claim);
        if(!claim.actor().equals(r.actor()) || !claim.requestKey().equals(r.requestKey()) ||
            !("OPEN".equals(r.state()) || "RELEASED".equals(r.state())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Core claim differs");
        return r;
    }
    public void release(Release release) {
        Result r=call("/release",release);
        if(!"RELEASED".equals(r.state()) ||
            !release.claim().actor().equals(r.actor()) ||
            !release.claim().requestKey().equals(r.requestKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core did not release Academic candidate claim");
    }
}

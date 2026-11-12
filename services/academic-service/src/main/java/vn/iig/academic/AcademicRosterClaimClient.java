package vn.iig.academic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * No Core PostgreSQL access. A lost release response is retried with exact
 * Academic command and outbox evidence after independent local commit.
 */
@Component
public class AcademicRosterClaimClient {
    public record Claim(String actor,String requestKey,String requestHash,
        String entityType,String entityId,long sourceEventId,
        long sourceRevision,long writerGeneration) {}
    public record Release(Claim claim,String candidateCommandId,String outboxHash) {}
    public record Result(String state,String actor,String requestKey) {}
    public record Summary(long openClaims,long releasedClaims,long auditEvents) {}

    private final RestClient core;
    private final String key;
    public AcademicRosterClaimClient(@Value("${iig.core.url}") String url,
            @Value("${iig.core.internal-key}") String secret) {
        if(secret==null || secret.isBlank())
            throw new IllegalArgumentException("Academic roster Core claim key required");
        this.key=secret;
        SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2500);f.setReadTimeout(8000);
        core=RestClient.builder().baseUrl(url).requestFactory(f).build();
    }
    private Result post(String suffix,Object body) {
        try {
            var result=core.post()
                .uri("/internal/academic/roster-candidate-claims"+suffix)
                .header("X-Internal-Key",key).body(body).retrieve().body(Result.class);
            if(result==null || result.actor()==null || result.requestKey()==null ||
                result.state()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Incomplete Core roster claim acknowledgement");
            return result;
        } catch(RestClientResponseException ex) {
            if(ex.getStatusCode().value()==409)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core student roster claim conflicts with original evidence",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core student roster claim unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core student roster claim network unavailable",ex);
        }
    }
    public Result acquire(Claim claim) {
        Result result=post("",claim);
        if(!claim.actor().equals(result.actor()) ||
            !claim.requestKey().equals(result.requestKey()) ||
            !("OPEN".equals(result.state()) || "RELEASED".equals(result.state())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core roster claim response does not match requested command");
        return result;
    }
    public void release(Release release) {
        Result result=post("/release",release);
        if(!"RELEASED".equals(result.state()) ||
            !release.claim().actor().equals(result.actor()) ||
            !release.claim().requestKey().equals(result.requestKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core did not release exact committed roster candidate");
    }
    public Summary summary() {
        try {
            Summary summary=core.get()
                .uri("/internal/academic/roster-candidate-claims/summary")
                .header("X-Internal-Key",key)
                .retrieve().body(Summary.class);
            if(summary==null || summary.openClaims()<0 ||
                summary.releasedClaims()<0 || summary.auditEvents()<0)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Incomplete Core roster claim ledger");
            return summary;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core roster claim ledger unreachable",ex);
        }
    }
}

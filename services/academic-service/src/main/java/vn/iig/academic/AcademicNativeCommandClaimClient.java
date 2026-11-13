package vn.iig.academic;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** A Core SQL claim serializes the native Academic transaction with handback. */
@Component
public class AcademicNativeCommandClaimClient {
    public record Claim(String actorUsername,String classId,String operation,
        String requestKey,String requestHash,long sessionGeneration,
        long rosterGeneration,long attendanceGeneration,long schedulingGeneration) {}
    public record Release(Claim claim,String eventId,String outboxHash) {}
    public record Receipt(String state,String actorUsername,String classId,
        String operation,String requestKey,String eventId,String outboxHash) {}
    public record Inspection(Claim claim,String state,
                             String eventId,String outboxHash) {}
    private final RestClient core;
    private final String key;

    public AcademicNativeCommandClaimClient(
            @Value("${iig.core.url}") String url,
            @Value("${iig.core.internal-key}") String key) {
        if(key==null || key.isBlank())
            throw new IllegalArgumentException("Native Academic claim key missing");
        this.key=key;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);factory.setReadTimeout(8000);
        core=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    private Receipt post(String suffix,Object body) {
        try {
            Receipt receipt=core.post()
                .uri("/internal/academic/native-command-claims"+suffix)
                .header("X-Internal-Key",key).body(body)
                .retrieve().body(Receipt.class);
            if(receipt==null || receipt.state()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core native Academic claim proof absent");
            return receipt;
        } catch(RestClientResponseException ex) {
            if(ex.getStatusCode().value()==409)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core native Academic claim conflicts with original evidence",ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core native Academic claim response unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Native claim response lost; Core may already have committed",ex);
        }
    }
    private static void verify(Claim c,Receipt r) {
        if(!c.actorUsername().equals(r.actorUsername()) ||
            !c.classId().equals(r.classId()) ||
            !c.operation().equals(r.operation()) ||
            !c.requestKey().equals(r.requestKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native claim receipt is for another actor/class/operation/key");
    }
    public Receipt acquire(Claim c) {
        if(c==null)throw new IllegalArgumentException("Native claim required");
        Receipt r=post("",c);verify(c,r);
        if(!"OPEN".equals(r.state()) && !"RELEASED".equals(r.state()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Invalid Core native command claim state");
        return r;
    }
    /**
     * Live Core receipt is the ONLY acceptable source for orphan recovery.
     * An unrecognized network response leaves the original claim OPEN.
     */
    public Inspection inspect(String actor,String operation,String requestKey) {
        try {
            Inspection original=core.get().uri(uri->uri
                .path("/internal/academic/native-command-claims/inspect")
                .queryParam("actor",actor)
                .queryParam("operation",operation)
                .queryParam("requestKey",requestKey).build())
                .header("X-Internal-Key",key)
                .retrieve().body(Inspection.class);
            if(original==null || original.claim()==null ||
                original.state()==null ||
                !java.util.Set.of("OPEN","RELEASED").contains(original.state()) ||
                !actor.equals(original.claim().actorUsername()) ||
                !operation.equals(original.claim().operation()) ||
                !requestKey.equals(original.claim().requestKey()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core native claim inspection returned incomplete original evidence");
            return original;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core native claim inspection unavailable; do not infer rollback",ex);
        }
    }

    public Receipt release(Release proof) {
        if(proof==null || proof.claim()==null || proof.eventId()==null ||
            proof.outboxHash()==null)
            throw new IllegalArgumentException("Exact native claim release required");
        Receipt r=post("/release",proof);
        verify(proof.claim(),r);
        if(!"RELEASED".equals(r.state()) || r.eventId()==null ||
            !proof.eventId().equalsIgnoreCase(r.eventId()) ||
            !proof.outboxHash().equals(r.outboxHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core did not release original immutable Academic outbox event");
        return r;
    }
}

package vn.iig.scheduling;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.scheduling.SchedulingModels.*;

/** Internal HTTP-only integration. Scheduling has no Core DB credentials. */
@Component
public class SchedulingCoreClient {
    private final RestClient client;
    private final String key;

    public SchedulingCoreClient(@Value("${iig.core.url}") String url,
                                @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("SCHEDULING_INTERNAL_API_KEY required");
        key=internalKey;
        SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2500);
        factory.setReadTimeout(8000);
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }


    public record Viewer(String employeeId,String username,String role) {}

    public Viewer currentViewer(String authorization) {
        if(authorization==null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        try {
            Viewer viewer=client.get().uri("/internal/scheduling/assignments/viewer")
                .header("Authorization",authorization)
                .header("X-Internal-Key",key)
                .retrieve().body(Viewer.class);
            if(viewer==null || viewer.username()==null || viewer.role()==null)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
            return viewer;
        } catch(HttpClientErrorException e) {
            if(e.getStatusCode().value()==401)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid session");
            if(e.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account inactive");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity bridge unavailable");
        } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity bridge unavailable");
        }
    }

    public List<AssignmentProjectionRepository.AssignmentSnapshot> exportAssignments() {
        try {
            AssignmentProjectionRepository.AssignmentSnapshot[] rows=client.get()
                .uri("/internal/scheduling/assignments/export")
                .header("X-Internal-Key",key).retrieve()
                .body(AssignmentProjectionRepository.AssignmentSnapshot[].class);
            return rows==null?List.of():Arrays.asList(rows);
        } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment import unavailable");
        }
    }

    public record AssignmentManifestSummary(long rowCount,long versionSum,long pendingCount) {}

    /** An independent non-outbox-draining manifest for ownership cutover checks. */
    public AssignmentManifestSummary assignmentManifestSummary() {
        try {
            AssignmentManifestSummary result=client.get()
                .uri("/internal/scheduling/assignments/manifest/summary")
                .header("X-Internal-Key",key)
                .retrieve().body(AssignmentManifestSummary.class);
            if(result==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment manifest summary missing");
            return result;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment manifest summary unavailable",ex);
        }
    }

    public List<AssignmentProjectionRepository.AssignmentSnapshot> assignmentManifest(
            String afterSource,String afterId,int limit) {
        try {
            AssignmentProjectionRepository.AssignmentSnapshot[] rows=client.get()
                .uri(builder->builder.path("/internal/scheduling/assignments/manifest")
                    .queryParam("afterSource",afterSource)
                    .queryParam("afterId",afterId)
                    .queryParam("limit",limit).build())
                .header("X-Internal-Key",key)
                .retrieve().body(AssignmentProjectionRepository.AssignmentSnapshot[].class);
            if(rows==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment manifest page missing");
            return Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment manifest page unavailable",ex);
        }
    }

    public List<AssignmentProjectionRepository.AssignmentSnapshot> pendingAssignments(int limit) {
        try {
            AssignmentProjectionRepository.AssignmentSnapshot[] entries=client.get()
                .uri(uri->uri.path("/internal/scheduling/assignments/changes")
                    .queryParam("limit",limit).build())
                .header("X-Internal-Key",key).retrieve()
                .body(AssignmentProjectionRepository.AssignmentSnapshot[].class);
            if(entries==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Empty Core assignment changes response");
            return Arrays.asList(entries);
        } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment changes unavailable");
        }
    }

    public void acknowledgeAssignments(
            List<AssignmentProjectionRepository.AssignmentSnapshot> entries) {
        if(entries==null || entries.isEmpty()) return;
        record Ack(String sourceType,String id,long version) {}
        List<Ack> acknowledgements=entries.stream()
            .map(e->new Ack(e.sourceType(),e.id(),e.version())).toList();
        try {
            client.post().uri("/internal/scheduling/assignments/ack/batch")
                .header("X-Internal-Key",key).body(acknowledgements)
                .retrieve().toBodilessEntity();
        } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment change acknowledgement unavailable");
        }
    }

    public Actor currentActor(String authorization) {
        if(authorization==null || !authorization.startsWith("Bearer "))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Bearer token required");
        try {
            Actor actor=client.get().uri("/internal/scheduling/actor")
                .header("Authorization",authorization)
                .header("X-Internal-Key",key).retrieve().body(Actor.class);
            if(actor==null || actor.employeeId()==null || actor.role()==null)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active staff profile required");
            return actor;
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==401)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session invalid");
            if(ex.getStatusCode().value()==403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active employee required");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity source unavailable");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity source unavailable");
        }
    }

    public List<Slot> exportLegacy() {
        try {
            Slot[] rows=client.get().uri("/internal/scheduling/availability/export")
                .header("X-Internal-Key",key).retrieve().body(Slot[].class);
            return rows==null?List.of():Arrays.asList(rows);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Availability import unavailable");
        }
    }

    public void guardEditable(Slot slot) {
        try {
            client.post().uri("/internal/scheduling/availability/guard")
                .header("X-Internal-Key",key)
                .body(slot).retrieve().toBodilessEntity();
        } catch(HttpClientErrorException ex) {
            if(ex.getStatusCode().value()==409)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cancel existing assignments before changing this availability");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not verify assigned schedule conflicts");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not verify assigned schedule conflicts");
        }
    }

    public void relayAudit(AvailabilityRepository.AuditEvent event) {
        try {
            client.post().uri("/internal/scheduling/availability/audit")
                .header("X-Internal-Key",key)
                .body(new AuditMessage(event.eventId(),event.username(),
                    event.action(),event.entityId()))
                .retrieve().toBodilessEntity();
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core audit relay unavailable");
        }
    }

    private record AuditMessage(String eventId,String username,
                                String action,String entityId) {}

    public void project(Slot slot) {
        try {
            client.post().uri("/internal/scheduling/availability/project")
                .header("X-Internal-Key",key)
                .body(slot).retrieve().toBodilessEntity();
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core availability projection unavailable");
        }
    }
}

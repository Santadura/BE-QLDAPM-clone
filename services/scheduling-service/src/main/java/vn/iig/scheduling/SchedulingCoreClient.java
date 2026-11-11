package vn.iig.scheduling;

import java.util.Arrays;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

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

    public record WriterClaimRequest(String actorUsername,String managerEmployeeId,
                                     String operation,String requestKey) {}
    public record WriterClaimReceipt(String actorUsername,String operation,
                                     String requestKey,String status) {}

    /**
     * This durable Core claim serializes with QUIESCED -> CORE handback.
     * MUST succeed before Scheduling commits a separate assignment write.
     * A lost response is safely retried with the same actor/operation/key.
     * A claim never auto-expires: uncertain partial work requires audit.
     */
    public void acquireAssignmentWriterClaim(String actor,String managerEmployeeId,
                                             String operation,String requestKey) {
        try {
            WriterClaimReceipt receipt=client.post()
                .uri("/internal/scheduling/assignments/claims")
                .header("X-Internal-Key",key)
                .body(new WriterClaimRequest(actor,managerEmployeeId,
                    operation,requestKey))
                .retrieve().body(WriterClaimReceipt.class);
            if(receipt==null || !actor.equals(receipt.actorUsername()) ||
                    !operation.equals(receipt.operation()) ||
                    !requestKey.equals(receipt.requestKey()) ||
                    !"PENDING".equals(receipt.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core returned an incompatible Scheduling writer claim");
        } catch(HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core no longer permits the independent Scheduling writer",ex);
        } catch(HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "CM assignment writer claim denied by Core",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Durable Core writer claim unavailable",ex);
        }
    }

    public record TeacherClaimEvidence(boolean found,String actorUsername,
        String requestKey,String managerEmployeeId,String claimStatus,
        long referenceEventCount,String referenceEventId,
        String referenceScheduleId,String referencePayloadHash,
        long referenceRevision,boolean coreTeacherRowExists) {}

    /**
     * Read-only, key-protected Core claim evidence. Missing, partial or
     * unavailable responses NEVER authorize a claim to be removed.
     */
    public TeacherClaimEvidence teacherClaimEvidence(String actor,String requestKey) {
        try {
            TeacherClaimEvidence result=client.get()
                .uri(uri->uri.path("/internal/scheduling/assignments/claims/teacher/diagnose")
                    .queryParam("actorUsername",actor)
                    .queryParam("requestKey",requestKey).build())
                .header("X-Internal-Key",key)
                .retrieve().body(TeacherClaimEvidence.class);
            if(result==null || !actor.equals(result.actorUsername()) ||
                    !requestKey.equals(result.requestKey()) ||
                    result.referenceEventCount()<0 || result.referenceEventCount()>1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core teacher claim evidence is incomplete or mismatched");
            return result;
        } catch(HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core claim diagnostic unavailable",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core claim diagnostic transport unavailable",ex);
        }
    }

    public record AssignmentWriterState(String mode) {}

    /**
     * Refuse independent writes while Core's legacy writer is active.
     * This read is NOT an atomic cross-DB lock: the Core reference consumer
     * also enforces QUIESCED under its own transaction before applying events.
     */
    public void requireCoreWriterQuiesced() {
        try {
            AssignmentWriterState state=client.get()
                .uri("/internal/scheduling/assignments/writer-state")
                .header("X-Internal-Key",key)
                .retrieve().body(AssignmentWriterState.class);
            if(state==null || !"QUIESCED".equals(state.mode()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core assignment writer must be quiesced");
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment writer state unavailable",ex);
        }
    }

    public record DurableAssignmentReferenceReceipt(
            String eventId,long revision,String payloadHash,
            String teacherId,String classId,LocalDate date,
            LocalTime startTime,LocalTime endTime,String status,String assignedBy) {
        public DurableAssignmentReferenceReceipt(String eventId,long revision,
                                                  String payloadHash) {
            this(eventId,revision,payloadHash,null,null,null,null,null,null,null);
        }
    }

    /** Read-only exact Core receipt; never assume a 503/timeout means rollback. */
    public DurableAssignmentReferenceReceipt committedAssignmentReceipt(
            String sourceType,String scheduleId,String eventId) {
        try {
            DurableAssignmentReferenceReceipt receipt=client.get()
                .uri(builder->builder
                    .path("/internal/scheduling/assignments/reference/receipt/event/{eventId}")
                    .queryParam("sourceType",sourceType)
                    .queryParam("scheduleId",scheduleId).build(eventId))
                .header("X-Internal-Key",key)
                .retrieve().body(DurableAssignmentReferenceReceipt.class);
            if(receipt==null || receipt.eventId()==null || receipt.payloadHash()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core reference receipt response incomplete");
            return receipt;
        } catch(HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core does not confirm this immutable reference event",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core reference receipt lookup unavailable",ex);
        }
    }

    public record AssignmentReferenceReceipt(String eventId,long revision,String outcome) {}
    public record AssignmentReferenceEnvelope(
        String eventId,String actorUsername,String operation,String requestKey,
        String sourceType,String scheduleId,long revision,String eventKind,
        String payloadJson,String payloadHash) {}

    /**
     * Private, feature-gated Core reference receiver. This must return only
     * after the Core projection and receipt have committed together. It can
     * consume standalone STAFF events or the guarded revision-1 TEACHING
     * reference endpoint. A TEACHING domain writer and public cutover are
     * still disabled; this client is only a private reference transport.
     */
    public AssignmentReferenceReceipt applyAssignmentReference(
            AssignmentDomainOutboxRepository.Event event) {
        try {
            AssignmentReferenceReceipt result=client.post()
                .uri("TEACHING".equals(event.sourceType()) ?
                    "/internal/scheduling/assignments/reference/teaching" :
                    "/internal/scheduling/assignments/reference")
                .header("X-Internal-Key",key)
                .body(new AssignmentReferenceEnvelope(
                    event.eventId(),event.actorUsername(),event.operation(),
                    event.requestKey(),event.sourceType(),event.scheduleId(),
                    event.revision(),event.eventKind(),event.payloadJson(),
                    event.payloadHash()))
                .retrieve().body(AssignmentReferenceReceipt.class);
            if(result==null || result.eventId()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core reference receiver returned no acknowledgement");
            return result;
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core assignment reference receiver unavailable",ex);
        }
    }

    public record AcademicSessionFacts(String classId,long classVersion,String classStatus,
            LocalDate classStart,LocalDate classEnd,String sessionId,
            long sessionVersion,String sessionStatus,LocalDate date,
            LocalTime startTime,LocalTime endTime,String classCode,String className) {
        // Compatibility for unit fixtures that only exercise session
        // revisions; real Core responses include the class display names.
        public AcademicSessionFacts(String classId,long classVersion,
            String classStatus,LocalDate classStart,LocalDate classEnd,
            String sessionId,long sessionVersion,String sessionStatus,
            LocalDate date,LocalTime startTime,LocalTime endTime) {
            this(classId,classVersion,classStatus,classStart,classEnd,
                 sessionId,sessionVersion,sessionStatus,date,startTime,endTime,null,null);
        }
    }

    /** Versioned Academic session facts: diagnostic, never a mutation lease. */
    public AcademicSessionFacts academicSession(String classId,LocalDate date,
                                               LocalTime start,LocalTime end) {
        try {
            AcademicSessionFacts result=client.get()
                .uri(builder->builder.path("/internal/scheduling/academic/session")
                    .queryParam("classId",classId).queryParam("date",date)
                    .queryParam("startTime",start).queryParam("endTime",end).build())
                .header("X-Internal-Key",key).retrieve()
                .body(AcademicSessionFacts.class);
            if(result==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session facts missing");
            return result;
        } catch(HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Planned academic session no longer exists",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session version service unavailable",ex);
        }
    }

    public record AcademicLease(String leaseId,String classId,String sessionId,
                                long classVersion,long sessionVersion,Instant expiresAt) {}
    private record AcademicLeaseRequest(String classId,String sessionId,
                                        long classVersion,long sessionVersion,String commandKey) {}

    /**
     * PREPARATION ONLY. Core refuses to issue leases while Core is the live
     * assignment writer. A future distributed command must renew and verify
     * this lease and compensate partial writes before enabling public routes.
     */
    public AcademicLease reserveAcademicSession(String classId,String sessionId,
                                                long classVersion,long sessionVersion,
                                                String commandKey) {
        try {
            AcademicLease lease=client.post().uri("/internal/scheduling/academic/leases")
                .header("X-Internal-Key",key)
                .body(new AcademicLeaseRequest(classId,sessionId,classVersion,sessionVersion,commandKey))
                .retrieve().body(AcademicLease.class);
            if(lease==null || lease.leaseId()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Empty Academic lease response");
            return lease;
        } catch(HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session unavailable or Core writes not quiesced",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session lease service unavailable",ex);
        }
    }

    public AcademicLease renewAcademicSession(String leaseId) {
        try {
            AcademicLease lease=client.post()
                .uri("/internal/scheduling/academic/leases/{id}/renew",leaseId)
                .header("X-Internal-Key",key).retrieve().body(AcademicLease.class);
            if(lease==null || lease.leaseId()==null)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Academic lease renewal returned no data");
            return lease;
        } catch(HttpClientErrorException.Conflict ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session lease expired or version changed",ex);
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session renewal unavailable",ex);
        }
    }

    public void releaseAcademicSession(String leaseId) {
        try {
            client.delete().uri("/internal/scheduling/academic/leases/{id}",leaseId)
                .header("X-Internal-Key",key).retrieve().toBodilessEntity();
        } catch(RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Academic session lease release unavailable",ex);
        }
    }

    public record AssignmentManifestSummary(long rowCount,long versionSum,long pendingCount,
                                            long liveSchedules,long trackedLiveSchedules) {}

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

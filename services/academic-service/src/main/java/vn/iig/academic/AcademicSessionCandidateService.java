package vn.iig.academic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicitly NON-AUTHORITATIVE candidate command. Can only stage a proposed
 * mutation when all Core writers are quiesced and a complete full-history
 * readiness proof is stable. It never updates live sessions or publishes.
 */
@Service
public class AcademicSessionCandidateService {
    public record Request(String requestKey,String classId,String sessionId,
        long expectedEventId,long expectedRevision,String action,String date,
        String startTime,String endTime,String status) {}
    private final AcademicCoreClient identity;
    private final AcademicSessionCutoverReadiness readiness;
    private final AcademicSessionSourceClient source;
    private final AcademicSessionCandidateRepository candidates;
    private final AcademicCandidateClaimClient claims;

    public AcademicSessionCandidateService(AcademicCoreClient identity,
            AcademicSessionCutoverReadiness readiness,
            AcademicSessionSourceClient source,
            AcademicSessionCandidateRepository candidates,
            AcademicCandidateClaimClient claims) {
        this.identity=identity;this.readiness=readiness;this.source=source;
        this.candidates=candidates;this.claims=claims;
    }

    @Transactional
    public AcademicSessionCandidateRepository.Receipt stage(String bearer,Request req) {
        if(req==null || req.requestKey()==null ||
                !req.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
                req.classId()==null ||
                !req.classId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                req.sessionId()==null ||
                !req.sessionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                req.expectedEventId()<1 || req.expectedRevision()<1 ||
                req.action()==null || !Set.of("RESCHEDULE","CANCEL").contains(req.action()) ||
                req.status()==null ||
                !("RESCHEDULE".equals(req.action())?"PLANNED".equals(req.status()):
                    "CANCELLED".equals(req.status())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid Academic candidate id, source revision and action required");
        LocalDate day;
        LocalTime from;
        LocalTime to;
        try {
            day=LocalDate.parse(req.date());
            from=LocalTime.parse(req.startTime());
            to=LocalTime.parse(req.endTime());
        } catch(DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid Academic candidate date and times required",ex);
        }
        if(!to.isAfter(from))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic candidate end time must follow start time");

        var actor=identity.currentPrincipal(bearer);
        if(actor==null || actor.username()==null ||
                !actor.username().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
                !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Private Academic candidate requires a live Admin JWT");
        // Even an Admin must pass Core's class-specific live ACL check.
        identity.sessionRevision(req.classId(),bearer);

        var evidence=readiness.verify(); // full source history, 3 QUIESCED fences
        String canonical="academic-candidate-v1\n"+req.classId()+"\n"+
            req.sessionId()+"\n"+req.expectedEventId()+"\n"+
            req.expectedRevision()+"\n"+req.action()+"\n"+day+"\n"+
            from+"\n"+to+"\n"+req.status();
        String digest=AcademicSessionSourceRepository.sha256(canonical);
        var plan=new AcademicSessionCandidateRepository.Plan(
            actor.username(),req.requestKey(),digest,req.classId(),req.sessionId(),
            req.expectedEventId(),req.expectedRevision(),req.action(),
            day,from,to,req.status(),evidence.academicGeneration(),
            evidence.attendanceGeneration(),evidence.schedulingGeneration());
        // The independently committed Core claim serializes with the
        // Core writer-control handback before Academic writes candidate facts.
        var bound=new AcademicCandidateClaimClient.Claim(
            actor.username(),req.requestKey(),digest,req.classId(),req.sessionId(),
            req.expectedEventId(),req.expectedRevision(),
            evidence.academicGeneration(),evidence.attendanceGeneration(),
            evidence.schedulingGeneration());
        claims.acquire(bound);
        var receipt=candidates.stage(plan); // one Academic PG transaction
        var end=source.writerFence();
        if(!"QUIESCED".equals(end.academicMode()) ||
                !"QUIESCED".equals(end.attendanceMode()) ||
                !"QUIESCED".equals(end.schedulingMode()) ||
                end.academicGeneration()!=evidence.academicGeneration() ||
                end.attendanceGeneration()!=evidence.attendanceGeneration() ||
                end.schedulingGeneration()!=evidence.schedulingGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic candidate writer generations changed; local transaction rolled back");
        return receipt;
    }

    /**
     * Called ONLY after stage's separate Academic transaction commits.
     * A timeout on Core release leaves OPEN and safely blocks Core handback.
     * Retrying the SAME key reuses immutable local command/outbox evidence.
     */
    public void acknowledge(String bearer,Request request,
            AcademicSessionCandidateRepository.Receipt receipt) {
        // An HTTP retry must carry the EXACT staged receipt; a matching
        // command UUID alone is not sufficient to release a Core claim.
        if(request==null || receipt==null ||
            receipt.commandId()==null ||
            !receipt.commandId().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") ||
            request.requestKey()==null || request.classId()==null ||
            request.sessionId()==null || request.action()==null ||
            request.status()==null ||
            request.expectedEventId()<1 || request.expectedRevision()<1 ||
            !request.requestKey().equals(receipt.requestKey()) ||
            !request.classId().equals(receipt.classId()) ||
            !request.sessionId().equals(receipt.sessionId()) ||
            request.expectedEventId()!=receipt.sourceEventId() ||
            request.expectedRevision()!=receipt.sourceRevision() ||
            !"STAGED".equals(receipt.state()) ||
            !(("RESCHEDULE".equals(request.action()) &&
                "PLANNED".equals(request.status())) ||
              ("CANCEL".equals(request.action()) &&
                "CANCELLED".equals(request.status()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic candidate receipt differs from original staged command");
        final LocalDate day;
        final LocalTime from;
        final LocalTime to;
        try {
            day=LocalDate.parse(request.date());
            from=LocalTime.parse(request.startTime());
            to=LocalTime.parse(request.endTime());
        } catch(DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Malformed Academic session retry date or time",ex);
        }
        if(!to.isAfter(from))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Academic session retry interval is invalid");

        var actor=identity.currentPrincipal(bearer);
        if(actor==null || actor.username()==null ||
            !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Active Admin required for Core claim release");

        var proof=candidates.committedEvidence(
            actor.username(),request.requestKey(),receipt.commandId());
        String canonical="academic-candidate-v1\n"+request.classId()+"\n"+
            request.sessionId()+"\n"+request.expectedEventId()+"\n"+
            request.expectedRevision()+"\n"+request.action()+"\n"+
            day+"\n"+from+"\n"+to+"\n"+request.status();
        String digest=AcademicSessionSourceRepository.sha256(canonical);
        if(!digest.equals(proof.digest()) ||
            !request.classId().equals(proof.classId()) ||
            !request.sessionId().equals(proof.sessionId()) ||
            request.expectedEventId()!=proof.eventId() ||
            request.expectedRevision()!=proof.revision())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Submitted Academic claim differs from committed local evidence");
        var claim=new AcademicCandidateClaimClient.Claim(
            proof.actor(),proof.key(),proof.digest(),proof.classId(),proof.sessionId(),
            proof.eventId(),proof.revision(),proof.academicGeneration(),
            proof.attendanceGeneration(),proof.schedulingGeneration());
        claims.release(new AcademicCandidateClaimClient.Release(
            claim,receipt.commandId(),proof.outboxHash()));
        // Local acknowledgement only after Core's transactional RELEASED
        // receipt. A crash at this edge is healed by the bounded reconciler.
        candidates.recordHandoff(proof);
    }
}

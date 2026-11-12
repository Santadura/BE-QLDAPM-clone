package vn.iig.academic;

import java.math.BigDecimal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable independent write REHEARSAL for TARGET/RESULT only.
 * No Core release claim and NO transfer of authoritative writer rights.
 * A separate future ownership protocol must complete before publication.
 */
@Service
public class AcademicRosterCandidateService {
    public record Request(String requestKey,String entityType,String entityId,
        long expectedEventId,long expectedRevision,String action,
        BigDecimal proposedValue) {}

    private final AcademicCoreClient identity;
    private final AcademicRosterCutoverReadiness readiness;
    private final AcademicRosterSourceClient source;
    private final AcademicRosterCandidateRepository candidates;

    public AcademicRosterCandidateService(AcademicCoreClient identity,
            AcademicRosterCutoverReadiness readiness,
            AcademicRosterSourceClient source,
            AcademicRosterCandidateRepository candidates) {
        this.identity=identity;this.readiness=readiness;
        this.source=source;this.candidates=candidates;
    }

    @Transactional
    public AcademicRosterCandidateRepository.Receipt stage(
            String bearer,Request request) {
        if(request==null || request.requestKey()==null ||
            !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            request.entityType()==null ||
            !Set.of("TARGET","RESULT").contains(request.entityType()) ||
            request.entityId()==null ||
            !request.entityId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
            request.expectedEventId()<1 || request.expectedRevision()<1 ||
            request.action()==null ||
            !Set.of("UPSERT","DELETE").contains(request.action()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Valid immutable Academic target/result request required");

        BigDecimal number=request.proposedValue();
        if("UPSERT".equals(request.action())) {
            if(number==null || number.signum()<0 || number.scale()>2 ||
                number.compareTo(new BigDecimal(
                    "TARGET".equals(request.entityType())?"999999.99":"999.99"))>0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Academic target/result value out of range");
            number=number.setScale(2);
        } else if(number!=null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "DELETE proposals must not include a numeric value");
        }

        var actor=identity.currentPrincipal(bearer);
        if(actor==null || actor.username()==null ||
            !actor.username().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}") ||
            !"ROLE_ADMIN".equals(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Private Academic target/result candidate requires current Core Admin");

        var before=readiness.verify();
        String canonical="academic-roster-candidate-v1\n"+request.entityType()+"\n"+
            request.entityId()+"\n"+request.expectedEventId()+"\n"+
            request.expectedRevision()+"\n"+request.action()+"\n"+
            (number==null?"DELETE":number.toPlainString());
        String digest=AcademicSessionSourceRepository.sha256(canonical);
        var plan=new AcademicRosterCandidateRepository.Plan(actor.username(),
            request.requestKey(),digest,request.entityType(),
            request.entityId(),request.expectedEventId(),
            request.expectedRevision(),before.writerGeneration(),
            request.action(),number);

        var result=candidates.stage(plan); // independent Academic SQL transaction
        var after=source.writerFence();
        if(after==null || !"QUIESCED".equals(after.mode()) ||
            after.generation()!=before.writerGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core Academic roster writer generation changed: rollback local candidate");
        return result; // outer Spring @Transactional commits BOTH candidate + outbox
    }
}

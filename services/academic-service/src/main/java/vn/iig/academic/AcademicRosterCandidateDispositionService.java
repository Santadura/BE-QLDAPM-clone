package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Core evidence before/after immutable Academic transaction.
 * REHEARSAL_DISCARDED is NOT promotion to an authoritative write.
 * Core writer must be CORE, not merely a temporarily frozen snapshot.
 */
@Service
public class AcademicRosterCandidateDispositionService {
    public record Request(String requestKey,String commandId) {}
    public record Receipt(String requestKey,String commandId,String disposition) {}
    private final AcademicCoreClient identity;
    private final AcademicRosterSourceClient source;
    private final AcademicRosterClaimClient claims;
    private final AcademicRosterCandidateDispositionRepository dispositions;

    public AcademicRosterCandidateDispositionService(AcademicCoreClient identity,
            AcademicRosterSourceClient source,AcademicRosterClaimClient claims,
            AcademicRosterCandidateDispositionRepository dispositions) {
        this.identity=identity;this.source=source;
        this.claims=claims;this.dispositions=dispositions;
    }

    @Transactional
    public Receipt discard(String bearer,Request request) {
        if(request==null || request.requestKey()==null ||
            !request.requestKey().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ||
            request.commandId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Academic rehearsal command identity required");
        var actor=identity.currentPrincipal(bearer);
        if(actor==null || !"ROLE_ADMIN".equals(actor.role()) ||
            actor.username()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Live Core Admin required to close Academic rehearsal");
        var first=source.writerFence();
        var firstClaims=claims.summary();
        if(first==null || !"CORE".equals(first.mode()) ||
            firstClaims==null || firstClaims.openClaims()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core must own roster writes and have zero unresolved claims");
        dispositions.discard(actor.username(),request.requestKey(),
            request.commandId(),actor.username());
        var last=source.writerFence();
        var lastClaims=claims.summary();
        if(!first.equals(last) || !firstClaims.equals(lastClaims) ||
            !"CORE".equals(last.mode()) || lastClaims.openClaims()!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core writer generation or claim ledger changed during disposition");
        return new Receipt(request.requestKey(),request.commandId(),
            "REHEARSAL_DISCARDED");
    }
}

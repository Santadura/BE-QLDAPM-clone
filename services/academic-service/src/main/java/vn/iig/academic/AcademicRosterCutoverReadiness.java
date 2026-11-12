package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only prerequisite to a future Academic student/roster write cutover.
 *
 * Core's SQL statement triggers must already block ALL four source writers,
 * then a complete cross-PostgreSQL event and typed projection proof must pass
 * without any writer-generation change (including CORE->QUIESCED ABA).
 *
 * This does NOT authorize routing student, target, result or membership
 * mutations to Academic, and does not change the writer mode.
 */
@Service
public class AcademicRosterCutoverReadiness {
    public record Evidence(long writerGeneration,long sourceEvents,
        long lastEventId,long students,long memberships,
        long distinctEntities) {}

    private final AcademicRosterSourceClient core;
    private final AcademicRosterReadiness parity;
    private final AcademicRosterClaimClient claims;

    public AcademicRosterCutoverReadiness(AcademicRosterSourceClient core,
            AcademicRosterReadiness parity,AcademicRosterClaimClient claims) {
        this.core=core;
        this.parity=parity;
        this.claims=claims;
    }

    private static ResponseStatusException blocked(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Academic roster cutover not ready: "+reason);
    }

    public Evidence verify() {
        var before=core.writerFence();
        if(before==null || !"QUIESCED".equals(before.mode()) ||
            before.generation()<2)
            throw blocked("Core student, membership, target and result writers must be QUIESCED");
        // An OPEN Core claim is a handback barrier even if the complete
        // Academic event history is otherwise identical. Read the ledger
        // before AND after parity so an acquire/release race cannot pass.
        var beforeClaims=claims.summary();
        if(beforeClaims==null || beforeClaims.openClaims()!=0)
            throw blocked("unreleased Core roster candidate claim exists");
        var history=parity.verify();
        var after=core.writerFence();
        var afterClaims=claims.summary();
        if(afterClaims==null || afterClaims.openClaims()!=0)
            throw blocked("Core roster candidate became OPEN during proof");
        if(!before.equals(after) || !"QUIESCED".equals(after.mode()) ||
            !beforeClaims.equals(afterClaims))
            throw blocked("Core roster writer or claim ledger changed during immutable history proof");
        return new Evidence(after.generation(),history.sourceEvents(),
            history.lastEventId(),history.students(),history.memberships(),
            history.distinctEntities());
    }
}

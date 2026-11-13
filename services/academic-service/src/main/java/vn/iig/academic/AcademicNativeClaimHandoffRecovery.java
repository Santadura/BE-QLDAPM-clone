package vn.iig.academic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recover the crash window AFTER an independent native Academic COMMIT and
 * BEFORE a Core claim-release acknowledgement was recorded locally.
 * Never fabricates a Core release if the original local outbox is absent.
 * OPEN Core claims require a fresh frozen, generation-stable multi-service
 * proof; an already RELEASED claim requires exact original event+SHA only.
 *
 * OFF by default and never exposed through Gateway.
 */
@Service
@ConditionalOnProperty(name="iig.academic.native-claim-recovery-enabled",
    havingValue="true")
public class AcademicNativeClaimHandoffRecovery {
    private static final Logger log=
        LoggerFactory.getLogger(AcademicNativeClaimHandoffRecovery.class);
    private final AcademicNativeSessionRepository journal;
    private final AcademicNativeCommandClaimClient core;
    private final AcademicFullCutoverReadiness readiness;

    public AcademicNativeClaimHandoffRecovery(
            AcademicNativeSessionRepository journal,
            AcademicNativeCommandClaimClient core,
            AcademicFullCutoverReadiness readiness) {
        this.journal=journal;this.core=core;this.readiness=readiness;
    }

    @Scheduled(initialDelayString="${iig.academic.native-claim-recovery-initial-ms:23000}",
        fixedDelayString="${iig.academic.native-claim-recovery-poll-ms:11000}")
    public void tick() {
        try {
            for(var pending:journal.pendingClaimHandoffs(25)) {
                try { recover(pending); }
                catch(Exception ex) {
                    log.warn("Native Academic Core claim remains unresolved for {}/{}: {}",
                        pending.classId(),pending.requestKey(),
                        ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Native Academic handoff scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public void recover(AcademicNativeSessionRepository.PendingHandoff p) {
        if(p==null || p.eventId()==null || p.eventHash()==null ||
            p.requestHash()==null)
            throw new IllegalArgumentException(
                "Original Academic committed event and digest required");
        var local=journal.committedEvidence(
            p.actor(),p.classId(),p.operation(),p.requestKey(),p.requestHash());
        if(!p.eventId().equalsIgnoreCase(local.eventId()) ||
            !p.eventHash().equals(local.eventHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Academic recovery local event is not immutable original");
        var remote=core.inspect(p.actor(),p.operation(),p.requestKey());
        var claim=remote.claim();
        if(claim==null ||
            !p.actor().equals(claim.actorUsername()) ||
            !p.classId().equals(claim.classId()) ||
            !p.operation().equals(claim.operation()) ||
            !p.requestKey().equals(claim.requestKey()) ||
            !p.requestHash().equals(claim.requestHash()) ||
            p.sessionGeneration()!=claim.sessionGeneration() ||
            p.rosterGeneration()!=claim.rosterGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Academic local event and Core claim epochs do not match");

        if("OPEN".equals(remote.state())) {
            // A previously crashed command must still be admitted against
            // all four frozen writer generations. No blind cleanup/expiry.
            var before=readiness.verifyStaging();
            if(before.sessionGeneration()!=claim.sessionGeneration() ||
                before.rosterGeneration()!=claim.rosterGeneration() ||
                before.attendanceGeneration()!=claim.attendanceGeneration() ||
                before.schedulingGeneration()!=claim.schedulingGeneration())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core writer generation changed since original native commit");
            var after=readiness.verifyStaging();
            if(!before.equals(after))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cross-service source changed during native claim recovery");
            core.release(new AcademicNativeCommandClaimClient.Release(
                claim,p.eventId(),p.eventHash()));
        } else if("RELEASED".equals(remote.state())) {
            // Recovery of a lost local ACK may happen after Core handback,
            // when frozen-readiness no longer applies. The exact committed
            // Core release receipt, NOT a stale source snapshot, is authority.
            if(remote.eventId()==null ||
                !p.eventId().equalsIgnoreCase(remote.eventId()) ||
                !p.eventHash().equals(remote.outboxHash()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Released Core native claim has different event identity");
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Core native claim has an unrecognized state");
        }
        journal.recordClaimHandoff(p);
    }
}

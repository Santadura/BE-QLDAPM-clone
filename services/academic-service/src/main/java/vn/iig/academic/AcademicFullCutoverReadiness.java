package vn.iig.academic;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Diagnostic-only coordinated barrier across four Core writer fences and
 * THREE independent PostgreSQL projections. There is deliberately NO
 * corresponding method to turn any writer on.
 *
 * Two complete rounds detect source, writer generation, claim, outbox and
 * projection drift, including a Core QUIESCED -> CORE -> QUIESCED ABA.
 * Successful evidence is a prerequisite, never live ownership permission.
 */
@Service
public class AcademicFullCutoverReadiness {
    // Preserve the original full-readiness JSON field names for existing
    // operator tools. New independent service proof is strictly additive.
    public record Evidence(long rosterGeneration,long sessionGeneration,
        long attendanceGeneration,long schedulingGeneration,
        long rosterEvents,long sessionEvents,long rosterLastEventId,
        long sessionLastEventId,long rosterClaimAuditEvents,
        long sessionClaimAuditEvents,
        AcademicDependentCutoverClient.Proof downstream) {

        public Evidence(AcademicCrossDomainCutoverReadiness.Evidence a,
                AcademicDependentCutoverClient.Proof d) {
            this(a.rosterGeneration(),a.sessionGeneration(),
                a.attendanceGeneration(),a.schedulingGeneration(),
                a.rosterEvents(),a.sessionEvents(),a.rosterLastEventId(),
                a.sessionLastEventId(),a.rosterClaimAuditEvents(),
                a.sessionClaimAuditEvents(),d);
        }

        // Java-only convenience for existing callers; never change the
        // established flat HTTP response by serializing a second nested copy.
        @com.fasterxml.jackson.annotation.JsonIgnore
        public AcademicCrossDomainCutoverReadiness.Evidence academic() {
            return new AcademicCrossDomainCutoverReadiness.Evidence(
                rosterGeneration,sessionGeneration,attendanceGeneration,
                schedulingGeneration,rosterEvents,sessionEvents,
                rosterLastEventId,sessionLastEventId,
                rosterClaimAuditEvents,sessionClaimAuditEvents);
        }
    }
    private final AcademicCrossDomainCutoverReadiness academic;
    private final AcademicDependentCutoverClient downstream;

    public AcademicFullCutoverReadiness(
            AcademicCrossDomainCutoverReadiness academic,
            AcademicDependentCutoverClient downstream) {
        this.academic=academic;this.downstream=downstream;
    }

    public Evidence verify() {
        var a0=academic.verify();
        var d0=downstream.verify();
        var a1=academic.verify();
        var d1=downstream.verify();
        if(a0==null || d0==null || !a0.equals(a1) || !d0.equals(d1))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cross-service cutover evidence changed during independent scans");
        return new Evidence(a1,d1);
    }
}

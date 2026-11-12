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
    private final AcademicNativeSessionRepository nativeSessions;

    public AcademicFullCutoverReadiness(
            AcademicCrossDomainCutoverReadiness academic,
            AcademicDependentCutoverClient downstream,
            AcademicNativeSessionRepository nativeSessions) {
        this.academic=academic;this.downstream=downstream;
        this.nativeSessions=nativeSessions;
    }

    /** Strict production gate never promotes REHEARSAL_ONLY mutations. */
    public Evidence verify() {return check(true);}

    /** Private rehearsal may add inert, atomic Academic events. */
    public Evidence verifyStaging() {return check(false);}

    private Evidence check(boolean production) {
        var n0=nativeSessions.rehearsalSummary();
        if(n0==null || n0.commands()!=n0.events() ||
            (production && (n0.activations()!=0 || n0.commands()!=0)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Native Academic rehearsal is not a committed business cutover");
        var a0=academic.verify();
        var d0=downstream.verify();
        var a1=academic.verify();
        var d1=downstream.verify();
        var n1=nativeSessions.rehearsalSummary();
        // Verify that Payroll's attendance/scheduling writer generation is
        // exactly the same frozen Core generation seen by Academic. A
        // completed projection from another writer epoch is not finality.
        if(d0!=null && d0.payroll()!=null && a0!=null &&
           (d0.payroll().path("attendanceGeneration").asLong(-1)!=a0.attendanceGeneration() ||
            d0.payroll().path("schedulingGeneration").asLong(-1)!=a0.schedulingGeneration()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Payroll downstream writer generation differs from Academic proof");
        if(n1==null || !n0.equals(n1) ||
            a0==null || d0==null || d0.payroll()==null ||
            !a0.equals(a1) || !d0.equals(d1) ||
            d1.payroll()==null ||
            d1.payroll().path("attendanceGeneration").asLong(-1)!=a1.attendanceGeneration() ||
            d1.payroll().path("schedulingGeneration").asLong(-1)!=a1.schedulingGeneration())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cross-service cutover evidence changed during independent scans");
        return new Evidence(a1,d1);
    }
}

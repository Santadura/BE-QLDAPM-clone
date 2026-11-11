package vn.iig.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Each completion invokes a SEPARATE Spring transaction via its own bean. */
@Service
@ConditionalOnProperty(name="iig.scheduling.academic-lease-completion-enabled",
    havingValue="true")
public class TeacherAcademicLeaseCompletionJob {
    private static final Logger log=
        LoggerFactory.getLogger(TeacherAcademicLeaseCompletionJob.class);
    private final TeacherAcademicLeaseCompletionService service;
    public TeacherAcademicLeaseCompletionJob(TeacherAcademicLeaseCompletionService service) {
        this.service=service;
    }
    @Scheduled(initialDelay=15000,fixedDelay=8000)
    public void sweep() {
        try {
            for(var key:service.pending(25)) {
                try { service.complete(key.actor(),key.requestKey()); }
                catch(Exception ex) {
                    log.warn("Confirmed teacher Academic lease {} completion pending: {}",
                        key.requestKey(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Confirmed teacher Academic lease cleanup deferred: {}",
                ex.getClass().getSimpleName());
        }
    }
}

package vn.iig.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Restart-safe reconciliation across the Attendance/Core commit boundary.
 * This worker never invokes the teacher attendance PUT: it only reads a
 * durable Core receipt and then commits the identical outcome locally.
 * Missing/revoked/unavailable Core proof keeps PENDING (fail closed).
 */
@Component
@ConditionalOnProperty(name="iig.attendance.command-recovery-enabled",
    havingValue="true")
public class AttendanceCommandRecovery {
    private static final Logger log=
        LoggerFactory.getLogger(AttendanceCommandRecovery.class);
    private final AttendanceCommandInbox inbox;
    private final AttendanceCommittedReceiptClient core;

    public AttendanceCommandRecovery(AttendanceCommandInbox inbox,
            AttendanceCommittedReceiptClient core) {
        this.inbox=inbox;this.core=core;
    }

    @Scheduled(initialDelayString="${iig.attendance.command-recovery-initial-ms:15000}",
               fixedDelayString="${iig.attendance.command-recovery-poll-ms:7000}")
    public void tick() {
        try {
            for(var item:inbox.pending(25)) {
                try { recover(item); }
                catch(Exception ex) {
                    log.warn("Attendance outcome still uncertain for {}: {}",
                        item.scheduleId(),ex.getClass().getSimpleName());
                }
            }
        } catch(Exception ex) {
            log.warn("Attendance pending command scan deferred: {}",
                ex.getClass().getSimpleName());
        }
    }

    public boolean recover(AttendanceCommandInbox.Pending item) {
        if(item==null)
            throw new IllegalArgumentException("Attendance inbox identity required");
        var evidence=core.confirmed(item);
        if(evidence==null)return false;
        // complete() checks digest and the exact previously stored response
        // under its own local PostgreSQL transaction, including replay.
        inbox.complete(item.actor(),item.classId(),item.scheduleId(),
            item.requestKey(),item.requestHash(),evidence);
        return true;
    }
}

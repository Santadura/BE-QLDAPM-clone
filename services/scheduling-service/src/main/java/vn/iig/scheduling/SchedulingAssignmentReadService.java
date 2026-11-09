package vn.iig.scheduling;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** FE-compatible scheduling GETs, secured using live Core identity state. */
@Service
public class SchedulingAssignmentReadService {
    private static final Logger log=LoggerFactory.getLogger(SchedulingAssignmentReadService.class);
    private final AssignmentProjectionRepository repo;
    private final SchedulingCoreClient core;

    public SchedulingAssignmentReadService(AssignmentProjectionRepository repo,SchedulingCoreClient core) {
        this.repo=repo;this.core=core;
    }

    /**
     * Read barrier: don't return the asynchronously cached copy while a
     * committed Core assignment change is still waiting in the outbox.
     * This preserves read-after-write through Gateway with no shared SQL.
     * Core remains the authoritative write source in this migration phase.
     */
    private void synchronizeCommittedChanges() {
        repo.requireReady();
        final int limit=500;
        for(int i=0;i<20;i++) {
            var changed=core.pendingAssignments(limit);
            if(changed.isEmpty()) {
                // Local independent assignment writes may have committed
                // before Core's durable reference projection. Avoid exposing
                // those rows as final while a command is unresolved.
                repo.requireReferenceSettledForReads();
                return;
            }
            // Local DB commit must succeed before Core acknowledgement.
            repo.applyBatch(changed);
            core.acknowledgeAssignments(changed);
            // A short batch is not proof that the feed is drained: a different
            // Core transaction may have committed while this batch was being
            // applied. Check again and return only after an empty feed read.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Scheduling assignment synchronization backlog too large");
    }

    private LocalDate monday(LocalDate d) {
        return (d==null?LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")):d).with(DayOfWeek.MONDAY);
    }

    public List<Map<String,Object>> teaching(String authorization,LocalDate week) {
        var actor=core.currentViewer(authorization);
        if(!Set.of("TC","ADMIN").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Teacher schedule management forbidden");
        synchronizeCommittedChanges();
        return repo.list("TEACHING",null,null,monday(week),false);
    }

    public List<Map<String,Object>> staff(String authorization,LocalDate week,String role) {
        var actor=core.currentViewer(authorization);
        if(!Set.of("CM","ADMIN").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Staff schedule management forbidden");
        String requested=role==null?null:role.trim().toUpperCase(Locale.ROOT);
        if(requested!=null && !Set.of("CS","SALE").contains(requested))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"role must be CS or SALE");
        synchronizeCommittedChanges();
        return repo.list("STAFF",null,requested,monday(week),false);
    }

    public List<Map<String,Object>> mine(String authorization,LocalDate week) {
        var actor=core.currentViewer(authorization);
        if(!Set.of("TEACHER","CS","SALE").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Worker role required");
        if(actor.employeeId()==null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Active worker profile required");
        synchronizeCommittedChanges();
        return "TEACHER".equals(actor.role()) ?
            repo.list("TEACHING",actor.employeeId(),null,monday(week),true):
            repo.list("STAFF",actor.employeeId(),null,monday(week),true);
    }

    @Scheduled(initialDelay=2000,fixedDelay=5000)
    public void initialize() {
        if(repo.initialized()) return;
        try {
            var original=core.exportAssignments();
            repo.importInitial(original);
            log.info("Imported {} core teaching/staff assignments into Scheduling read model",
                original.size());
        } catch(Exception e) {
            log.warn("Scheduling assignment read-model bootstrap pending: {}",e.getMessage());
        }
    }
}

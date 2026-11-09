package vn.iig.scheduling;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Phase 8b-3a: explicit no-cutover-until-consistent gate.
 *
 * Source data is fetched exclusively over Core's private HTTP manifest.
 * Scheduling NEVER connects to the Core database. All versions, tombstones,
 * denormalized FE JSON and source outbox completeness are checked.
 *
 * Verification is diagnostic: a successful scan is a prerequisite to a future
 * controlled write-ownership cutover, not itself proof of concurrent safety.
 */
@Service
public class AssignmentCutoverVerificationService {
    private static final int PAGE_SIZE=250;
    private static final int MAX_PAGES=100; // fail closed for oversized scans
    private final AssignmentProjectionRepository readModel;
    private final SchedulingCoreClient core;

    public record Report(boolean ready,String state,long sourceRows,long localRows,
                         long writeShadowRows,long checkedRows,long mismatches,long pendingChanges,
                         long unresolvedCommands,
                         long liveSchedules,long trackedLiveSchedules,
                         long sourceVersionSum,List<String> sampleMismatchKeys) {}

    public AssignmentCutoverVerificationService(AssignmentProjectionRepository readModel,
                                                SchedulingCoreClient core) {
        this.readModel=readModel;
        this.core=core;
    }

    public Report verify() {
        readModel.requireReady();
        var before=core.assignmentManifestSummary();
        String afterSource="";
        String afterId="";
        long checked=0;
        long mismatched=0;
        List<String> samples=new ArrayList<>();
        boolean complete=false;

        for(int page=0;page<MAX_PAGES;page++) {
            var rows=core.assignmentManifest(afterSource,afterId,PAGE_SIZE);
            if(rows==null || rows.size()>PAGE_SIZE)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core assignment manifest returned an invalid page");
            if(rows.isEmpty()) {
                complete=true;
                break;
            }
            for(var item:rows) {
                if(item==null || item.sourceType()==null || item.id()==null ||
                    !after(afterSource,afterId,item.sourceType(),item.id()))
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Core assignment manifest cursor did not advance");
                if(!readModel.matchesManifest(item)) {
                    mismatched++;
                    if(samples.size()<10) samples.add(item.sourceType()+":"+item.id());
                }
                checked++;
                afterSource=item.sourceType();
                afterId=item.id();
            }
            if(rows.size()<PAGE_SIZE) {
                complete=true;
                break;
            }
        }
        if(!complete)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Assignment cutover verification exceeds 25000 rows; use a paged migration job");

        long localRows=readModel.countAllAssignments();
        long shadowRows=readModel.countShadowAssignments();
        long unresolved=readModel.pendingAssignmentCommands();
        var current=core.assignmentManifestSummary();
        boolean unchanged=before.rowCount()==current.rowCount() &&
            before.versionSum()==current.versionSum() &&
            before.liveSchedules()==current.liveSchedules() &&
            before.trackedLiveSchedules()==current.trackedLiveSchedules();
        boolean completeOutbox=current.liveSchedules()==current.trackedLiveSchedules();
        boolean noPending=before.pendingCount()==0 && current.pendingCount()==0;
        boolean matching=checked==current.rowCount() &&
            localRows==current.rowCount() && shadowRows==current.rowCount() && mismatched==0;
        String state=!unchanged ? "SOURCE_CHANGED_DURING_SCAN" :
            !completeOutbox ? "CORE_OUTBOX_INCOMPLETE" :
            !noPending ? "PENDING_PROJECTION_CHANGES" :
            !matching ? "PROJECTION_DRIFT" :
            unresolved>0 ? "UNRESOLVED_ASSIGNMENT_COMMANDS" : "READY";

        return new Report("READY".equals(state),state,current.rowCount(),localRows,
            shadowRows,checked,mismatched,current.pendingCount(),unresolved,current.liveSchedules(),
            current.trackedLiveSchedules(),current.versionSum(),List.copyOf(samples));
    }

    private boolean after(String previousSource,String previousId,String source,String id) {
        int bySource=source.compareTo(previousSource);
        return bySource>0 || (bySource==0 && id.compareTo(previousId)>0);
    }
}

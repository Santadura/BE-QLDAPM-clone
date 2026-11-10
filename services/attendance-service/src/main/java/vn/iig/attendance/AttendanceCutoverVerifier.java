package vn.iig.attendance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, fail-closed cutover preflight. It does not enable a writer.
 * The incremental Core event queue being empty is insufficient proof:
 * this checks every previously acknowledged event's latest source state,
 * including tombstones, against the independent Attendance PostgreSQL.
 */
@Service
public class AttendanceCutoverVerifier {
    private static final int PAGE_SIZE=250;
    private static final int MAX_PAGES=100;
    private static final int MAX_MISMATCH_KEYS=20;
    private final AttendanceSourceClient source;
    private final AttendanceEventProjectionRepository projection;
    private final AttendanceEventRelay relay;

    public record Report(boolean ready,String state,long sourceRows,
                         long verifiedRows,int mismatchCount,List<String> mismatchKeys) {}

    public AttendanceCutoverVerifier(AttendanceSourceClient source,
            AttendanceEventProjectionRepository projection,AttendanceEventRelay relay) {
        this.source=source;
        this.projection=projection;
        this.relay=relay;
    }

    public Report verify() {
        // Never serve a successful report while the committed Core feed is
        // backlogged. Outages propagate 503, not false readiness.
        relay.requireCaughtUp();
        var before=source.manifestSummary();
        if(before.sourceRows()>PAGE_SIZE*MAX_PAGES)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Attendance full manifest exceeds bounded verification capacity");

        List<String> mismatchKeys=new ArrayList<>();
        int errors=0;
        if(before.liveRows()!=before.trackedLiveRows()) {
            errors++;
            add(mismatchKeys,"CORE_UNTRACKED_LIVE_ROWS");
        }
        if(before.divergentRows()!=0) {
            errors++;
            add(mismatchKeys,"CORE_SOURCE_DIVERGENCE");
        }
        if(before.pendingEvents()!=0) {
            errors++;
            add(mismatchKeys,"CORE_PENDING_EVENTS");
        }

        long verified=0;
        String after="";
        Set<String> seen=new HashSet<>();
        for(int page=0;page<MAX_PAGES && verified<before.sourceRows();page++) {
            List<AttendanceSourceClient.ManifestRow> rows=source.manifest(after,PAGE_SIZE);
            if(rows==null || rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core Attendance manifest ended unexpectedly");
            if(rows.size()>PAGE_SIZE)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Oversized Core Attendance manifest page");
            for(var row:rows) {
                if(row==null || row.eventId()<=0 ||
                        row.teachingScheduleId()==null ||
                        row.teachingScheduleId().isBlank() ||
                        row.teachingScheduleId().length()>50 ||
                        !Set.of("UPSERT","DELETE").contains(row.eventKind()) ||
                        row.payloadJson()==null ||
                        !seen.add(row.teachingScheduleId())) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Invalid or duplicate Core Attendance manifest identity");
                }
                if(!row.sourceMatches() || !projection.matchesManifest(row)) {
                    errors++;
                    add(mismatchKeys,row.teachingScheduleId());
                }
                verified++;
                after=row.teachingScheduleId();
                if(verified>before.sourceRows())
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Core Attendance manifest grew during scan");
            }
        }
        if(verified!=before.sourceRows()) {
            errors++;
            add(mismatchKeys,"INCOMPLETE_SOURCE_MANIFEST");
        }
        long localCount=projection.projectedRowCount();
        if(localCount!=verified) {
            errors++;
            add(mismatchKeys,"ATTENDANCE_ORPHAN_OR_MISSING_ROWS");
        }
        var afterScan=source.manifestSummary();
        if(!before.equals(afterScan) || afterScan.pendingEvents()!=0) {
            errors++;
            add(mismatchKeys,"SOURCE_CHANGED_DURING_VERIFICATION");
        }
        return new Report(errors==0,errors==0?"READY":"MISMATCH",
            before.sourceRows(),verified,errors,List.copyOf(mismatchKeys));
    }

    private static void add(List<String> keys,String value) {
        if(keys.size()<MAX_MISMATCH_KEYS)keys.add(value);
    }
}

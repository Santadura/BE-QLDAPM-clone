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
    private final AttendanceCommandInbox commands;
    private final AttendanceNativeLedgerRepository nativeLedger;

    public record Report(boolean ready,String state,long sourceRows,
                         long verifiedRows,int mismatchCount,List<String> mismatchKeys) {}

    public AttendanceCutoverVerifier(AttendanceSourceClient source,
            AttendanceEventProjectionRepository projection,AttendanceEventRelay relay,
            AttendanceCommandInbox commands,AttendanceNativeLedgerRepository nativeLedger) {
        this.source=source;
        this.projection=projection;
        this.relay=relay;
        this.commands=commands;
        this.nativeLedger=nativeLedger;
    }

    /**
     * Stronger preflight than ordinary live parity. Both existing Core
     * writers must be QUIESCED, with unchanged monotonically increasing
     * generations across the entire scan. This still does NOT freeze
     * Academic class mutations or authorize Attendance independent writes.
     */
    public Report verifyFrozen() {
        var before=source.writerFence();
        if(!isFrozen(before))
            return new Report(false,"NOT_FROZEN",0,0,1,
                List.of("CORE_WRITERS_NOT_QUIESCED"));
        long pendingBefore=commands.pendingCount();
        if(pendingBefore!=0)
            return new Report(false,"UNRESOLVED_ATTENDANCE_COMMANDS",0,0,1,
                List.of("ATTENDANCE_PENDING_CORE_RESULT"));
        // Core parity alone is insufficient once independent Attendance
        // owns native mutations: an unconfirmed local native event may not
        // be present in Core's historical manifest yet. NEVER authorize
        // Core handback while that event still has uncertain reference fate.
        var nativeBefore=nativeLedger.recoverySummary();
        if(nativeBefore==null || nativeBefore.pending()!=0 ||
                nativeBefore.quarantined()!=0)
            return new Report(false,"UNRESOLVED_NATIVE_ATTENDANCE",0,0,1,
                List.of("NATIVE_ATTENDANCE_REFERENCE_UNCONFIRMED"));
        var report=verify();
        // Re-read the independent inbox after the full immutable source scan,
        // not only at its start. A concurrent PENDING command must invalidate
        // a previously complete projection snapshot.
        long pendingAfter=commands.pendingCount();
        var after=source.writerFence();
        var nativeAfter=nativeLedger.recoverySummary();
        if(nativeAfter==null || !nativeBefore.equals(nativeAfter) ||
                nativeAfter.pending()!=0 || nativeAfter.quarantined()!=0) {
            var errors=new ArrayList<>(report.mismatchKeys());
            if(errors.size()<MAX_MISMATCH_KEYS)
                errors.add("NATIVE_ATTENDANCE_REFERENCE_CHANGED_DURING_SCAN");
            return new Report(false,"UNRESOLVED_NATIVE_ATTENDANCE",
                report.sourceRows(),report.verifiedRows(),
                report.mismatchCount()+1,List.copyOf(errors));
        }
        if(pendingAfter!=0) {
            var errors=new ArrayList<>(report.mismatchKeys());
            if(errors.size()<MAX_MISMATCH_KEYS)
                errors.add("ATTENDANCE_COMMAND_STARTED_DURING_SCAN");
            return new Report(false,"UNRESOLVED_ATTENDANCE_COMMANDS",
                report.sourceRows(),report.verifiedRows(),
                report.mismatchCount()+1,List.copyOf(errors));
        }
        if(!before.equals(after) || !isFrozen(after)) {
            List<String> errors=new ArrayList<>(report.mismatchKeys());
            if(errors.size()<MAX_MISMATCH_KEYS)
                errors.add("WRITER_MODE_CHANGED_DURING_SCAN");
            return new Report(false,"MISMATCH",report.sourceRows(),
                report.verifiedRows(),report.mismatchCount()+1,List.copyOf(errors));
        }
        return report;
    }

    private static boolean isFrozen(AttendanceSourceClient.WriterFence fence) {
        return fence!=null && "QUIESCED".equals(fence.attendanceMode()) &&
            "QUIESCED".equals(fence.schedulingMode()) &&
            fence.attendanceGeneration()>0 && fence.schedulingGeneration()>0;
    }

    public Report verify() {
        // Never serve a successful report while the committed Core feed is
        // backlogged. Outages propagate 503, not false readiness.
        relay.requireCaughtUp();
        relay.requireHistoryCaughtUp();
        var before=source.manifestSummary();
        if(before.eventCount()>25000 || before.sourceRows()>PAGE_SIZE*MAX_PAGES)
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
        if(projection.historicalEventCount()!=before.eventCount() ||
                projection.historyCursor()<before.maxEventId() ||
                projection.historicalReceiptDivergence()!=0) {
            errors++;
            add(mismatchKeys,"INCOMPLETE_OR_DIVERGENT_ATTENDANCE_EVENT_HISTORY");
        }

        // Compare EVERY original source event with the independent immutable
        // event history, not only the newest event for each schedule.
        long historicalVerified=0;
        long lastHistoricalId=0;
        for(int page=0;page<125 && historicalVerified<before.eventCount();page++) {
            List<AttendanceSourceClient.Event> batch=source.history(lastHistoricalId,200);
            if(batch==null || batch.isEmpty() || batch.size()>200)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Core Attendance historical manifest ended unexpectedly");
            for(var event:batch) {
                if(event==null || event.eventId()<=lastHistoricalId ||
                        !projection.matchesHistoricalEvent(event)) {
                    errors++;
                    add(mismatchKeys,"ATTENDANCE_EVENT_HISTORY_MISMATCH");
                }
                if(event==null || event.eventId()<=lastHistoricalId)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Invalid Core Attendance historical manifest order");
                historicalVerified++;
                lastHistoricalId=event.eventId();
                if(historicalVerified>before.eventCount())
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Core Attendance event history grew during verification");
            }
        }
        if(historicalVerified!=before.eventCount()) {
            errors++;
            add(mismatchKeys,"INCOMPLETE_ATTENDANCE_HISTORY_MANIFEST");
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
                if(!row.sourceMatches() || !projection.matchesManifest(row) ||
                        !projection.matchesLedgerManifest(row)) {
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
        if(localCount!=verified || projection.ledgerRowCount()!=verified) {
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

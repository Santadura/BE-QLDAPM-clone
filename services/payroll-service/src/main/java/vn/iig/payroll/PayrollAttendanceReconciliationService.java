package vn.iig.payroll;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollContracts.*;

/**
 * Read-only, private Payroll / Attendance double-source reconciliation.
 * The public Payroll calculation STILL uses Core Work facts and has no
 * dependency on this preflight. This report never mutates Payroll ledgers.
 *
 * Core Work lists already filter to ASSIGNED + PRESENT/LATE teaching shifts.
 * Every such payable teacher shift must be supported by independent
 * Attendance evidence of the SAME schedule at the SAME Core event revision.
 * Staff shift payroll and salary-rate ownership remain out of scope.
 */
@Service
public class PayrollAttendanceReconciliationService {
    private static final int PAGE_SIZE=100;
    private static final int MAX_SCHEDULES=2500;
    private static final int MAX_MISMATCHES=20;
    private final PayrollCoreClient core;
    private final PayrollAttendanceEvidenceClient attendance;

    public record Report(boolean ready,String state,String period,int teacherShifts,
                         long sourceRevision,long sourceEventCount,
                         long attendanceGeneration,long schedulingGeneration,
                         int mismatches,List<String> mismatchScheduleIds) {}

    public PayrollAttendanceReconciliationService(PayrollCoreClient core,
            PayrollAttendanceEvidenceClient attendance) {
        this.core=core;this.attendance=attendance;
    }

    public Report compare(String period) {
        YearMonth month;
        try {
            if(period==null || !period.matches("\\d{4}-\\d{2}"))
                throw new IllegalArgumentException("format");
            month=YearMonth.parse(period);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Payroll attendance period must be YYYY-MM");
        }

        List<Facts> source=core.facts(month.toString());
        if(source==null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core Payroll source facts unavailable");

        Map<String,String> teachers=teacherShifts(source,month);
        if(teachers.isEmpty()) {
            var proof=attendance.readiness();
            if(!teachers.equals(teacherShifts(core.facts(month.toString()),month)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Teacher Payroll work source changed during reconciliation");
            return new Report(true,"NO_TEACHER_SHIFTS",month.toString(),
                0,proof.sourceRevision(),proof.sourceEventCount(),
                proof.attendanceGeneration(),proof.schedulingGeneration(),0,List.of());
        }

        List<String> ids=new ArrayList<>(teachers.keySet());
        List<String> mismatches=new ArrayList<>();
        int mismatchCount=0;
        PayrollAttendanceEvidenceClient.Snapshot first=null;
        for(int i=0;i<ids.size();i+=PAGE_SIZE) {
            List<String> batch=ids.subList(i,Math.min(ids.size(),i+PAGE_SIZE));
            var result=attendance.snapshot(batch);
            if(first==null)first=result;
            else if(first.sourceRevision()!=result.sourceRevision() ||
                    first.sourceEventCount()!=result.sourceEventCount() ||
                    first.attendanceGeneration()!=result.attendanceGeneration() ||
                    first.schedulingGeneration()!=result.schedulingGeneration())
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Core Attendance source changed between Payroll evidence pages");
            Set<String> payable=new HashSet<>();
            for(var evidence:result.evidence()) {
                if("UPSERT".equals(evidence.eventKind()) &&
                        Set.of("PRESENT","LATE").contains(evidence.status()))
                    payable.add(evidence.teachingScheduleId());
            }
            for(String expected:batch) {
                if(!payable.contains(expected)) {
                    mismatchCount++;
                    if(mismatches.size()<MAX_MISMATCHES)mismatches.add(expected);
                }
            }
        }
        if(!teachers.equals(teacherShifts(core.facts(month.toString()),month)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Teacher Payroll work source changed during reconciliation");
        return new Report(mismatchCount==0,mismatchCount==0?"MATCH":"MISMATCH",
            month.toString(),teachers.size(),first.sourceRevision(),
            first.sourceEventCount(),first.attendanceGeneration(),
            first.schedulingGeneration(),mismatchCount,List.copyOf(mismatches));
    }
    private static Map<String,String> teacherShifts(List<Facts> source,YearMonth month) {
        if(source==null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Core teacher Payroll source facts unavailable");
        Map<String,String> teachers=new LinkedHashMap<>();
        for(Facts item:source) {
            if(item==null || item.person()==null ||
                    !"ACTIVE".equals(item.person().status()) ||
                    !"TEACHER".equals(item.person().role())) continue;
            if(item.works()==null || item.person().employeeId()==null ||
                    item.person().employeeId().isBlank())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Missing Core Payroll teacher reference facts");
            for(Work work:item.works()) {
                if(work==null || work.scheduleId()==null ||
                        !work.scheduleId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}") ||
                        work.workDate()==null ||
                        !YearMonth.from(work.workDate()).equals(month) ||
                        !"TEACHING".equals(work.sourceType()) ||
                        teachers.putIfAbsent(work.scheduleId(),
                            item.person().employeeId())!=null)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Invalid or duplicated teacher Payroll source shift");
                if(teachers.size()>MAX_SCHEDULES)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Payroll Attendance reconciliation exceeds bounded capacity");
            }
        }
        return teachers;
    }
}

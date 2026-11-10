package vn.iig.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayrollAttendanceReconciliationServiceTest {
    private final PayrollCoreClient core=mock(PayrollCoreClient.class);
    private final PayrollAttendanceEvidenceClient attendance=
        mock(PayrollAttendanceEvidenceClient.class);
    private final PayrollAttendanceReconciliationService subject=
        new PayrollAttendanceReconciliationService(core,attendance);

    private static final Person teacher=new Person("teacher-1","A","T1","TEACHER","ACTIVE");
    private static final Person cs=new Person("cs-1","B","CS1","CS","ACTIVE");

    private static Work teaching(String id) {
        return new Work(id,LocalDate.of(2026,10,10),LocalTime.of(8,0),
            LocalTime.of(10,0),"TEACHING","IELTS",BigDecimal.valueOf(100000));
    }
    private static Work staff(String id) {
        return new Work(id,LocalDate.of(2026,10,10),LocalTime.of(8,0),
            LocalTime.of(10,0),"STAFF","SUPPORT",BigDecimal.valueOf(30000));
    }

    private static PayrollAttendanceEvidenceClient.Evidence evidence(
            String id,String status) {
        return new PayrollAttendanceEvidenceClient.Evidence(
            id,"UPSERT",status,9L,"a".repeat(64));
    }
    private static PayrollAttendanceEvidenceClient.Snapshot snapshot(
            long version,List<PayrollAttendanceEvidenceClient.Evidence> events) {
        return new PayrollAttendanceEvidenceClient.Snapshot(
            version,3,2,4,events);
    }

    @Test void onlyPayableTeacherShiftsMustHavePresentOrLateIndependentEvidence() {
        when(core.facts("2026-10")).thenReturn(List.of(
            new Facts(teacher,List.of(teaching("teach-1"),teaching("teach-2"))),
            new Facts(cs,List.of(staff("cs-shift-1")))));
        when(attendance.snapshot(List.of("teach-1","teach-2"))).thenReturn(
            snapshot(9,List.of(evidence("teach-1","PRESENT"),
                evidence("teach-2","LATE"))));
        var report=subject.compare("2026-10");
        assertTrue(report.ready());
        assertEquals("MATCH",report.state());
        assertEquals(2,report.teacherShifts());
        assertEquals(9L,report.sourceRevision());
        assertEquals(0,report.mismatches());
        verify(core,times(2)).facts("2026-10");
        verify(attendance).snapshot(List.of("teach-1","teach-2"));
        verify(attendance,never()).readiness();
    }

    @Test void absentOrDeletedEvidenceNeverMatchesCorePayableWork() {
        when(core.facts("2026-10")).thenReturn(List.of(
            new Facts(teacher,List.of(teaching("teach-1"),teaching("teach-2")))));
        when(attendance.snapshot(List.of("teach-1","teach-2"))).thenReturn(
            snapshot(9,List.of(evidence("teach-1","ABSENT"))));
        var report=subject.compare("2026-10");
        assertFalse(report.ready());
        assertEquals("MISMATCH",report.state());
        assertEquals(2,report.mismatches());
        assertEquals(List.of("teach-1","teach-2"),report.mismatchScheduleIds());
    }

    @Test void coreWorkChangedDuringVerificationMustNotReturnOptimisticMatch() {
        when(core.facts("2026-10")).thenReturn(
            List.of(new Facts(teacher,List.of(teaching("teach-1")))),
            List.of(new Facts(teacher,List.of(teaching("teach-2")))));
        when(attendance.snapshot(List.of("teach-1"))).thenReturn(
            snapshot(9,List.of(evidence("teach-1","PRESENT"))));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->subject.compare("2026-10")).getStatusCode());
    }

    @Test void emptyTeacherMonthStillVerifiesWholeIndependentSource() {
        when(core.facts("2026-10")).thenReturn(
            List.of(new Facts(cs,List.of(staff("staff-1")))));
        when(attendance.readiness()).thenReturn(snapshot(9,List.of()));
        var report=subject.compare("2026-10");
        assertTrue(report.ready());
        assertEquals("NO_TEACHER_SHIFTS",report.state());
        assertEquals(9,report.sourceRevision());
        verify(core,times(2)).facts("2026-10");
        verify(attendance).readiness();
    }

    @Test void overHundredTeacherShiftsRequireSameSourceGenerationEveryPage() {
        var works=new ArrayList<Work>();
        var first=new ArrayList<PayrollAttendanceEvidenceClient.Evidence>();
        for(int i=0;i<101;i++) {
            works.add(teaching("teach-"+i));
            if(i<100)first.add(evidence("teach-"+i,"PRESENT"));
        }
        when(core.facts("2026-10")).thenReturn(List.of(new Facts(teacher,works)));
        var batch=works.subList(0,100).stream().map(Work::scheduleId).toList();
        when(attendance.snapshot(batch)).thenReturn(snapshot(9,first));
        when(attendance.snapshot(List.of("teach-100")))
            .thenReturn(snapshot(10,List.of(evidence("teach-100","PRESENT"))));
        assertEquals(HttpStatus.CONFLICT,assertThrows(
            ResponseStatusException.class,
            ()->subject.compare("2026-10")).getStatusCode());
    }

    @Test void malformedPeriodOrDuplicateCoreTeacherShiftFailsClosed() {
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(
            ResponseStatusException.class,
            ()->subject.compare("2026-13")).getStatusCode());
        when(core.facts("2026-10")).thenReturn(List.of(
            new Facts(teacher,List.of(teaching("teach-1"),teaching("teach-1")))));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->subject.compare("2026-10")).getStatusCode());
        verifyNoInteractions(attendance);
    }

    @Test void independentSourceOutageIsNotMisrepresentedAsMatching() {
        when(core.facts("2026-10")).thenReturn(
            List.of(new Facts(teacher,List.of(teaching("teach-1")))));
        when(attendance.snapshot(List.of("teach-1"))).thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"offline"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(
            ResponseStatusException.class,
            ()->subject.compare("2026-10")).getStatusCode());
    }
}

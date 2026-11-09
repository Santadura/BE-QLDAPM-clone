package vn.iig.payroll;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import vn.iig.payroll.PayrollContracts.*;

class PayrollServiceTest {
    private final PayrollRepository db=mock(PayrollRepository.class);
    private final PayrollCoreClient core=mock(PayrollCoreClient.class);
    private final PayrollService service=new PayrollService(db,core);

    private final Person teacher=new Person("teacher-001","Teacher A","GV-001","TEACHER","ACTIVE");
    private final Person cs=new Person("cs-001","CS B","CS-001","CS","ACTIVE");
    private final PayrollRow teacherRow=new PayrollRow("p1","2026-10","teacher-001",
        "Teacher A","GV-001","TEACHER",1,BigDecimal.valueOf(2),BigDecimal.valueOf(300000),
        Instant.parse("2026-10-12T00:00:00Z"),"EMPLOYEE");

    @Test void importsHistoryOnceWithNoCoreWrites() {
        when(db.imported()).thenReturn(false);
        when(core.historical()).thenReturn(List.of());
        service.bootstrap();
        verify(db).importHistorical(List.of());
        verify(core).historical();
        verifyNoMoreInteractions(core);
    }

    @Test void teacherMayNotCalculatePayroll() {
        when(db.imported()).thenReturn(true);
        when(core.actor("teacher")).thenReturn(new Actor("teacher001","teacher-001","TEACHER"));
        when(core.people()).thenReturn(List.of(teacher));
        assertThrows(ResponseStatusException.class,()->service.calculate("teacher","2026-10",null));
        verify(db,never()).calculate(any(),any());
        verify(core,never()).facts(anyString());
    }

    @Test void tcMayOnlyCalculateTeacherPayroll() {
        when(db.imported()).thenReturn(true);
        when(core.actor("tc")).thenReturn(new Actor("tc001","emp-tc-001","TC"));
        when(core.people()).thenReturn(List.of(teacher,cs));
        when(core.facts("2026-10")).thenReturn(List.of(new Facts(teacher,List.of(
            new Work("schedule-1",LocalDate.parse("2026-10-12"),LocalTime.NOON,
                LocalTime.of(14,0),"TEACHING","IELTS",BigDecimal.valueOf(150000)))),
            new Facts(cs,List.of())));
        when(db.byPeriod("2026-10")).thenReturn(List.of(teacherRow));
        List<PayrollRow> rows=service.calculate("tc","2026-10",null);
        assertEquals(List.of(teacherRow),rows);
        verify(db).calculate(argThat(f->"teacher-001".equals(f.person().employeeId())),
            eq(java.time.YearMonth.of(2026,10)));
        verify(db,never()).calculate(argThat(f->"cs-001".equals(f.person().employeeId())),any());
    }

    @Test void tcCannotAccessCsSalaryEvenByQueryParameter() {
        when(db.imported()).thenReturn(true);
        when(core.actor("tc")).thenReturn(new Actor("tc001","emp-tc-001","TC"));
        when(core.people()).thenReturn(List.of(teacher,cs));
        assertThrows(ResponseStatusException.class,()->service.list("tc","2026-10","cs-001"));
        verify(db,never()).byPeriod(anyString());
    }

    @Test void selfReadButNotPeerRead() {
        when(db.imported()).thenReturn(true);
        when(core.actor("teacher")).thenReturn(new Actor("teacher001","teacher-001","TEACHER"));
        when(core.people()).thenReturn(List.of(teacher,cs));
        when(db.byPeriod("2026-10")).thenReturn(List.of(teacherRow));
        assertEquals(List.of(teacherRow),service.list("teacher","2026-10",null));
        assertThrows(ResponseStatusException.class,
            ()->service.detail("teacher","cs-001","2026-10"));
    }

    @Test void returnsUnavailableWhileHistoryMigrates() {
        when(core.actor("admin")).thenReturn(new Actor("admin",null,"ADMIN"));
        when(db.imported()).thenReturn(false);
        assertThrows(ResponseStatusException.class,()->service.list("admin","2026-10",null));
        verify(db,never()).byPeriod(anyString());
    }
}

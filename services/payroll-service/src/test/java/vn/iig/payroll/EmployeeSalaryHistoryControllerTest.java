
package vn.iig.payroll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmployeeSalaryHistoryControllerTest {
    @Test void forwardsOriginalHrApiAndJwtToPayrollOwner() throws Exception {
        PayrollService payroll=mock(PayrollService.class);
        var endpoint=new EmployeeSalaryHistoryController(payroll);
        var rows=java.util.List.<java.util.Map<String,Object>>of(
            java.util.Map.of("source","EMPLOYEE","period","2026-10"));
        when(payroll.employeeSalaryHistory("Bearer token","teacher-001")).thenReturn(rows);
        assertSame(rows,endpoint.list("Bearer token","teacher-001"));
        verify(payroll).employeeSalaryHistory("Bearer token","teacher-001");
        assertEquals("/api/employee-management",
            EmployeeSalaryHistoryController.class.getAnnotation(RequestMapping.class).value()[0]);
        var mapping=EmployeeSalaryHistoryController.class
            .getDeclaredMethod("list",String.class,String.class).getAnnotation(GetMapping.class);
        assertEquals("/{employeeId}/salaries",mapping.value()[0]);
    }
}

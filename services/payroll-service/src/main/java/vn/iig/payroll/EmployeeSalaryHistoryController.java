package vn.iig.payroll;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** FE-compatible employee salary list from independently owned Payroll DB. */
@RestController
@RequestMapping("/api/employee-management")
public class EmployeeSalaryHistoryController {
    private final PayrollService payroll;
    public EmployeeSalaryHistoryController(PayrollService payroll) {
        this.payroll=payroll;
    }
    @GetMapping("/{employeeId}/salaries")
    public List<Map<String,Object>> list(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String employeeId) {
        return payroll.employeeSalaryHistory(bearer,employeeId);
    }
}

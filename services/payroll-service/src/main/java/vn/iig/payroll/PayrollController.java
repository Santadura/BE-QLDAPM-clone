package vn.iig.payroll;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import vn.iig.payroll.PayrollContracts.*;

/** FE-compatible ledger API, replacing only the three ledger-owned routes. */
@RestController
@RequestMapping("/api/payroll")
public class PayrollController {
    private final PayrollService payroll;
    public PayrollController(PayrollService payroll) { this.payroll=payroll; }

    @GetMapping
    public List<PayrollRow> list(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam String period,
            @RequestParam(value="employeeId",required=false) String employeeId) {
        return payroll.list(bearer,period,employeeId);
    }

    @PostMapping("/calculate")
    public List<PayrollRow> calculate(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @RequestParam String period,
            @RequestParam(value="employeeId",required=false) String employeeId) {
        return payroll.calculate(bearer,period,employeeId);
    }

    @GetMapping("/{employeeId}/{period}")
    public PayrollDetail detail(
            @RequestHeader(value="Authorization",required=false) String bearer,
            @PathVariable String employeeId,@PathVariable String period) {
        return payroll.detail(bearer,employeeId,period);
    }
}

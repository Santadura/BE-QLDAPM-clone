package vn.iig.hr;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Legacy manager rank options sourced from HR's authoritative rank data. */
@RestController
@RequestMapping("/api/employee-management")
public class HrEmployeeRankOptionsController {
    private final SalaryRankService ranks;
    public HrEmployeeRankOptionsController(SalaryRankService ranks) { this.ranks=ranks; }
    @GetMapping("/salary-ranks")
    public List<Map<String,Object>> list(
            @RequestHeader(value="Authorization",required=false) String bearer) {
        return ranks.managerOptions(bearer);
    }
}

package vn.iig.hr;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.iig.hr.SalaryRankModels.*;

@RestController
@RequestMapping("/api/payroll/ranks")
public class SalaryRankController {
    private final SalaryRankService ranks;
    public SalaryRankController(SalaryRankService ranks) { this.ranks=ranks; }

    @GetMapping
    public List<RankView> list(@RequestHeader(value="Authorization",required=false) String auth,
                               @RequestParam(value="roleId",required=false) String roleId) {
        return ranks.list(auth,roleId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RankView create(@RequestHeader(value="Authorization",required=false) String auth,
                           @Valid @RequestBody RankRequest request) {
        return ranks.create(request,auth);
    }

    @PutMapping("/{id}")
    public RankView update(@RequestHeader(value="Authorization",required=false) String auth,
                           @PathVariable String id,
                           @Valid @RequestBody RankUpdate request) {
        return ranks.update(id,request,auth);
    }
}

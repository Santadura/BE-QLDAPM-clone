package vn.iig.hr;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Exact /api/profile/salary fields, rank values from HR-owned PostgreSQL. */
@RestController
@RequestMapping("/api/profile")
public class HrSelfSalaryController {
    public record CurrentSalaryResponse(
        String salaryRankId,String salaryRank,BigDecimal amount,LocalDate reviewedDate) {}

    private final HrCoreClient core;
    private final SalaryRankRepository ranks;
    public HrSelfSalaryController(HrCoreClient core,SalaryRankRepository ranks) {
        this.core=core;
        this.ranks=ranks;
    }
    @GetMapping("/salary")
    public CurrentSalaryResponse current(
            @RequestHeader(value="Authorization",required=false) String bearer) {
        HrCoreClient.SelfSalaryAssignment assignment=core.currentSalaryAssignment(bearer);
        if(!ranks.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "HR salary rank import pending");
        if(assignment.salaryRankId()==null)
            return new CurrentSalaryResponse(null,null,null,assignment.reviewedDate());
        var definition=ranks.get(assignment.salaryRankId());
        return new CurrentSalaryResponse(definition.id(),definition.rank(),
            definition.amount(),assignment.reviewedDate());
    }
}

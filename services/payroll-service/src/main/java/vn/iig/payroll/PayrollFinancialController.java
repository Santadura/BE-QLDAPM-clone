package vn.iig.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private financial summary for Reporting, sourced exclusively from Payroll's
 * authoritative ledger. Gateway exposes /api/** only; this never goes public.
 */
@RestController
@RequestMapping("/internal/payroll/financial")
public class PayrollFinancialController {
    private final PayrollRepository ledger;
    private final byte[] key;

    public PayrollFinancialController(PayrollRepository ledger,
            @Value("${iig.core.internal-key}") String internalKey) {
        if(internalKey==null || internalKey.isBlank())
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY is required");
        this.ledger=ledger;
        this.key=internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public List<PayrollRepository.MonthCost> costs(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestParam String from,@RequestParam String to) {
        byte[] supplied=provided==null?new byte[0]:provided.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(key,supplied))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Payroll internal key");
        final YearMonth first,last;
        try {
            first=YearMonth.parse(from);
            last=YearMonth.parse(to);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payroll date range");
        }
        if(first.isAfter(last) || first.plusYears(10).isBefore(last))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid payroll range");
        if(!ledger.imported())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payroll import pending");
        return ledger.monthlyCosts(first.toString(),last.toString());
    }
}

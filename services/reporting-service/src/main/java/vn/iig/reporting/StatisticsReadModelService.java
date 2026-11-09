package vn.iig.reporting;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Near-real-time, durable reporting projection. The initial migration adapter
 * reads a legacy snapshot over HTTP; domain events will later replace refreshes
 * once Academic, Scheduling and Payroll are independently authoritative.
 */
@Service
public class StatisticsReadModelService {
    public record Result(JsonNode payload, Instant refreshedAt) {}

    private final SnapshotRepository repository;
    private final ReportingCoreClient core;
    private final ReportingPayrollClient payroll;
    private final ObjectMapper mapper;
    private final int maxAgeSeconds;

    public StatisticsReadModelService(SnapshotRepository repository,
                                      ReportingCoreClient core, ReportingPayrollClient payroll,
                                      ObjectMapper mapper,
                                      @Value("${iig.snapshot.max-age-seconds:30}") int maxAgeSeconds) {
        if (maxAgeSeconds < 1 || maxAgeSeconds > 3600) {
            throw new IllegalArgumentException("Reporting snapshot freshness must be 1..3600 seconds");
        }
        this.repository = repository;
        this.core = core;
        this.payroll = payroll;
        this.mapper = mapper;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public Result load(StatisticsQuery q, String bearerToken) {
        // Check role and revocation on *every* call, including cached results.
        core.requireAdmin(bearerToken);
        SnapshotRepository.Snapshot cached = repository.get(q.key());
        if (cached != null
                && cached.refreshedAt().plus(Duration.ofSeconds(maxAgeSeconds)).isAfter(Instant.now())) {
            return new Result(parse(cached.payload()), cached.refreshedAt());
        }
        String content = core.currentStatistics(q);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Empty reporting source");
        }
        JsonNode json = overlayPayrollCosts(parse(content),q);
        SnapshotRepository.Snapshot stored = repository.put(q.key(), json.toString());
        return new Result(json, stored.refreshedAt());
    }

    private JsonNode overlayPayrollCosts(JsonNode source, StatisticsQuery q) {
        if(!(source instanceof ObjectNode json)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid reporting document");
        }
        boolean hasFinance=json.get("financial") instanceof ObjectNode;
        boolean hasMonthly=json.get("monthly") instanceof ArrayNode;
        if(!hasFinance && !hasMonthly) return json;

        String first=YearMonth.of(q.year(),1).toString();
        String last=YearMonth.of(q.year(),12).toString();
        List<ReportingPayrollClient.MonthCost> costs=payroll.monthly(first,last);
        Map<String,ReportingPayrollClient.MonthCost> byMonth=costs.stream()
            .collect(Collectors.toMap(ReportingPayrollClient.MonthCost::period,
                Function.identity(),(a,b)->a));
        if(hasFinance && !"DAY".equals(q.period())) {
            ObjectNode financial=(ObjectNode)json.get("financial");
            BigDecimal sum=BigDecimal.ZERO;
            long records=0;
            for(var row:costs) {
                if("MONTH".equals(q.period()) && !row.period().equals(
                        YearMonth.of(q.year(),q.month()).toString())) continue;
                sum=sum.add(row.salaryCost());
                records+=row.payrollRecords();
            }
            financial.put("salaryCost",sum);
            financial.put("payrollRecords",records);
        }
        if(hasMonthly) {
            for(JsonNode entry:(ArrayNode)json.get("monthly")) {
                if(!(entry instanceof ObjectNode month)) continue;
                int m=month.path("month").asInt(0);
                if(m<1 || m>12) continue;
                var cost=byMonth.get(YearMonth.of(q.year(),m).toString());
                month.put("salaryCost",cost==null?BigDecimal.ZERO:cost.salaryCost());
                // Keep profit unknown while source revenue is unavailable.
            }
        }
        return json;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid reporting source payload");
        }
    }
}

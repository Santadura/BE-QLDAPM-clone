package vn.iig.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Same HTTP path and JSON contract as the existing admin statistics screen. */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {
    private final StatisticsReadModelService model;

    public StatisticsController(StatisticsReadModelService model) {
        this.model = model;
    }

    @GetMapping
    public ResponseEntity<JsonNode> get(
            @RequestHeader(value="Authorization",required=false) String authorization,
            @RequestParam(defaultValue="YEAR") String period,
            @RequestParam(required=false) Integer year,
            @RequestParam(required=false) Integer month,
            @RequestParam(required=false)
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        StatisticsQuery q = StatisticsQuery.of(period, year, month, date);
        var result = model.load(q, authorization);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Reporting-Refreshed-At", result.refreshedAt().toString())
            .body(result.payload());
    }
}

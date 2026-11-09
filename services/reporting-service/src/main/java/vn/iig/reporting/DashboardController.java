package vn.iig.reporting;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Preserves GET /api/dashboard contract while serving private read model. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardReadModelService dashboard;

    public DashboardController(DashboardReadModelService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public ResponseEntity<JsonNode> get(
            @RequestHeader(value="Authorization",required=false) String authorization) {
        var value = dashboard.load(authorization);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Reporting-Refreshed-At", value.refreshedAt().toString())
            .body(value.payload());
    }
}

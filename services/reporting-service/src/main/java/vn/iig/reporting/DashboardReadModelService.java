package vn.iig.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dashboard read model scoped by verified current user, role and business day.
 * Core retains snapshot computation until domain source events are available.
 */
@Service
public class DashboardReadModelService {
    public record Result(JsonNode payload, Instant refreshedAt) {}
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Set<String> ROLES =
        Set.of("ROLE_ADMIN","ROLE_TC","ROLE_CM","ROLE_TEACHER","ROLE_SALE","ROLE_CS");

    private final DashboardRepository repository;
    private final ReportingCoreClient core;
    private final ObjectMapper mapper;
    private final int ttlSeconds;

    public DashboardReadModelService(DashboardRepository repository,
                                     ReportingCoreClient core,
                                     ObjectMapper mapper,
                                     @Value("${iig.dashboard.max-age-seconds:10}") int ttlSeconds) {
        if (ttlSeconds < 1 || ttlSeconds > 300) {
            throw new IllegalArgumentException("Dashboard TTL must be 1..300 seconds");
        }
        this.repository = repository;
        this.core = core;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    public Result load(String authorization) {
        // Authorization must be revalidated even when a local snapshot exists.
        ReportingCoreClient.Principal principal = core.currentPrincipal(authorization);
        if (!ROLES.contains(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Dashboard role not supported");
        }
        String key = key(principal, LocalDate.now(BUSINESS_ZONE));
        DashboardRepository.Snapshot cache = repository.get(key);
        if (cache != null && cache.refreshedAt()
                .plus(Duration.ofSeconds(ttlSeconds)).isAfter(Instant.now())) {
            return new Result(parse(cache.payload()), cache.refreshedAt());
        }

        String json = core.currentDashboard(principal.username());
        if (json == null || json.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Empty dashboard source");
        }
        JsonNode payload = parse(json);
        DashboardRepository.Snapshot saved = repository.put(key, payload.toString());
        return new Result(payload, saved.refreshedAt());
    }

    static String key(ReportingCoreClient.Principal actor, LocalDate date) {
        return "DASH:" + actor.username() + ":" + actor.role() + ":" + date;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Invalid dashboard source payload");
        }
    }
}

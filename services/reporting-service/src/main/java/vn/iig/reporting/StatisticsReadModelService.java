package vn.iig.reporting;

import java.time.Duration;
import java.time.Instant;

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
    private final ObjectMapper mapper;
    private final int maxAgeSeconds;

    public StatisticsReadModelService(SnapshotRepository repository,
                                      ReportingCoreClient core, ObjectMapper mapper,
                                      @Value("${iig.snapshot.max-age-seconds:30}") int maxAgeSeconds) {
        if (maxAgeSeconds < 1 || maxAgeSeconds > 3600) {
            throw new IllegalArgumentException("Reporting snapshot freshness must be 1..3600 seconds");
        }
        this.repository = repository;
        this.core = core;
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
        JsonNode json = parse(content);
        SnapshotRepository.Snapshot stored = repository.put(q.key(), json.toString());
        return new Result(json, stored.refreshedAt());
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

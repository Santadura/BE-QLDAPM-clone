package com.example.demo.controller;

import com.example.demo.dto.StatisticsDtos.StatisticsResponse;
import com.example.demo.service.StatisticsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Short-lived extraction adapter. The Reporting service owns its snapshot DB,
 * while this endpoint supplies the legacy materialized statistics until domain
 * owners publish independently consumable events.
 */
@RestController
@RequestMapping("/internal/reporting")
@ConditionalOnProperty(name = "iig.reporting.bridge-enabled", havingValue = "true")
public class ReportingInternalController {
    private final StatisticsService statistics;
    private final byte[] serviceKey;

    public ReportingInternalController(StatisticsService statistics,
            @Value("${iig.reporting.internal-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("REPORTING_INTERNAL_API_KEY is required");
        }
        this.statistics = statistics;
        this.serviceKey = key.getBytes(StandardCharsets.UTF_8);
    }

    private void requireServiceKey(String supplied) {
        byte[] provided = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(serviceKey, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid reporting service key");
        }
    }

    @GetMapping("/statistics")
    public StatisticsResponse statistics(
            @RequestHeader(value="X-Internal-Key", required=false) String key,
            @RequestParam(defaultValue="YEAR") String period,
            @RequestParam(required=false) Integer year,
            @RequestParam(required=false) Integer month,
            @RequestParam(required=false)
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        requireServiceKey(key);
        return statistics.internalStatistics(period, year, month, date);
    }
}

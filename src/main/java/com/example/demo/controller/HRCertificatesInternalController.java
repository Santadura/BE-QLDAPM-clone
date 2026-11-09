package com.example.demo.controller;

import com.example.demo.service.EmployeeManagementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private HR extraction adapter. Core certificates become a reference projection. */
@RestController
@RequestMapping("/internal/hr")
@ConditionalOnProperty(name="iig.hr.certificate-bridge-enabled", havingValue="true")
public class HRCertificatesInternalController {
    public record CertificateData(
        String certificateId, String employeeId, String certificateType,
        Double score, Double scoreSW, LocalDate issueDate, LocalDate expiryDate,
        String issuer, String certificateUrl, String status, Instant createdAt, long version
    ) {}

    private final JdbcTemplate db;
    private final EmployeeManagementService employees;
    private final byte[] key;

    public HRCertificatesInternalController(JdbcTemplate db, EmployeeManagementService employees,
            @Value("${iig.hr.internal-key}") String sharedKey) {
        if (sharedKey == null || sharedKey.isBlank()) {
            throw new IllegalArgumentException("HR_INTERNAL_API_KEY required");
        }
        this.db = db;
        this.employees = employees;
        this.key = sharedKey.getBytes(StandardCharsets.UTF_8);
    }

    private void requireKey(String provided) {
        byte[] other = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(key, other)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid HR service key");
        }
    }

    @GetMapping("/certificates/export")
    public List<CertificateData> export(
            @RequestHeader(value="X-Internal-Key",required=false) String provided) {
        requireKey(provided);
        return db.query("SELECT certificate_id,employee_id,certificate_type,score,score_sw," +
            "issue_date,expiry_date,issuer,certificate_url,status,created_at " +
            "FROM certificates ORDER BY created_at,certificate_id", (rs,index) ->
            new CertificateData(rs.getString(1),rs.getString(2),rs.getString(3),
                (Double)rs.getObject(4, Double.class),(Double)rs.getObject(5, Double.class),
                rs.getObject(6, LocalDate.class),rs.getObject(7, LocalDate.class),
                rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getTimestamp(11).toInstant(),1L));
    }

    @GetMapping("/employees/{employeeId}/access")
    public void requireEmployeeAccess(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @PathVariable String employeeId, Authentication actor) {
        requireKey(provided);
        // Existing EmployeeManagementService.detail enforces current JWT and
        // the Admin/TC/CM staff-role management scopes.
        employees.detail(employeeId, actor);
    }

    @PostMapping("/certificates/project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void project(
            @RequestHeader(value="X-Internal-Key",required=false) String provided,
            @RequestBody CertificateData event) {
        requireKey(provided);
        if (event == null || event.certificateId() == null || event.certificateId().isBlank()
                || event.certificateId().length() > 50 || event.employeeId() == null
                || event.employeeId().isBlank() || event.employeeId().length() > 50
                || event.certificateType() == null || event.certificateType().isBlank()
                || event.certificateType().length() > 30 || event.version() < 1
                || event.status()==null || !List.of("ACTIVE","INACTIVE").contains(event.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid certificate projection");
        }
        db.update("INSERT INTO hr_certificate_versions(certificate_id,version) VALUES(?,0) " +
            "ON CONFLICT DO NOTHING",event.certificateId());
        Long applied = db.queryForObject(
            "SELECT version FROM hr_certificate_versions WHERE certificate_id=? FOR UPDATE",
            Long.class,event.certificateId());
        if (applied != null && applied >= event.version()) return;

        db.update("INSERT INTO certificates(certificate_id,employee_id,certificate_type,score,score_sw," +
            "issue_date,expiry_date,issuer,certificate_url,status,created_at) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(certificate_id) DO UPDATE SET " +
            "employee_id=EXCLUDED.employee_id,certificate_type=EXCLUDED.certificate_type," +
            "score=EXCLUDED.score,score_sw=EXCLUDED.score_sw,issue_date=EXCLUDED.issue_date," +
            "expiry_date=EXCLUDED.expiry_date,issuer=EXCLUDED.issuer," +
            "certificate_url=EXCLUDED.certificate_url,status=EXCLUDED.status",
            event.certificateId(),event.employeeId(),event.certificateType(),
            event.score(),event.scoreSW(),event.issueDate(),event.expiryDate(),
            event.issuer(),event.certificateUrl(),event.status(),
            java.sql.Timestamp.from(event.createdAt()==null?Instant.now():event.createdAt()));
        db.update("UPDATE hr_certificate_versions SET version=?,updated_at=NOW() " +
            "WHERE certificate_id=?",event.version(),event.certificateId());
    }
}

package com.example.demo.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary HR Salary Rank migration adapter. The core salary_ranks table is
 * a local FK/reference projection, no longer the authoritatively writable rank
 * model in the microservices stack. Never expose /internal/** in Gateway.
 */
@RestController
@RequestMapping("/internal/hr/salary-ranks")
@ConditionalOnProperty(name="iig.hr.certificate-bridge-enabled",havingValue="true")
public class HRSalaryRanksInternalController {
    public record RankState(String id,String roleId,String rank,BigDecimal amount,
                            String status,Instant createdAt,long version) {}
    private final JdbcTemplate db;
    private final byte[] secret;

    public HRSalaryRanksInternalController(JdbcTemplate db,
            @Value("${iig.hr.internal-key}") String key) {
        if (key==null || key.isBlank()) throw new IllegalArgumentException("HR_INTERNAL_API_KEY required");
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }

    private void authorize(String supplied) {
        byte[] received=supplied==null?new byte[0]:supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(secret,received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid HR service key");
        }
    }

    @GetMapping("/export")
    public List<RankState> exportLegacy(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        authorize(key);
        return db.query("SELECT salary_rank_id,role_id,rank,amount,status,created_at " +
                "FROM salary_ranks ORDER BY salary_rank_id",
            (rs,i)->new RankState(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getBigDecimal(4),rs.getString(5),rs.getTimestamp(6).toInstant(),1));
    }

    @PostMapping("/project")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void project(@RequestHeader(value="X-Internal-Key",required=false) String key,
                        @RequestBody RankState value) {
        authorize(key);
        if (value==null || value.id()==null || value.id().isBlank()
                || value.id().length()>50 || value.roleId()==null
                || !Set.of("TEACHER","SALE","CS").contains(value.roleId())
                || value.rank()==null || value.rank().isBlank() || value.rank().length()>50
                || value.amount()==null || value.amount().signum()<0
                || value.status()==null || !Set.of("ACTIVE","INACTIVE").contains(value.status())
                || value.version()<1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid HR salary rank projection");
        }
        db.update("INSERT INTO hr_salary_rank_versions(salary_rank_id,version) VALUES(?,0) " +
            "ON CONFLICT DO NOTHING",value.id());
        Long applied=db.queryForObject(
            "SELECT version FROM hr_salary_rank_versions WHERE salary_rank_id=? FOR UPDATE",
            Long.class,value.id());
        if(applied!=null && applied>=value.version()) return;

        db.update("INSERT INTO salary_ranks(salary_rank_id,role_id,rank,amount,status,created_at) " +
            "VALUES(?,?,?,?,?,?) ON CONFLICT(salary_rank_id) DO UPDATE SET " +
            "role_id=EXCLUDED.role_id,rank=EXCLUDED.rank,amount=EXCLUDED.amount,status=EXCLUDED.status",
            value.id(),value.roleId(),value.rank(),value.amount(),value.status(),
            java.sql.Timestamp.from(value.createdAt()==null?Instant.now():value.createdAt()));
        db.update("UPDATE hr_salary_rank_versions SET version=?,updated_at=NOW() " +
            "WHERE salary_rank_id=?",value.version(),value.id());
    }
}

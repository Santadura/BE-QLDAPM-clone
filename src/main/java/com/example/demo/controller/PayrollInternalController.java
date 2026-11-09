package com.example.demo.controller;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transitional contract supplying the Payroll service with verified people
 * and immutable-at-read-time attendance/schedule facts. Core never writes the
 * new payroll ledger and Payroll cannot access Core's database.
 *
 * Protected by an independent service key and not routed by Gateway.
 */
@RestController
@RequestMapping("/internal/payroll")
@ConditionalOnProperty(name="iig.payroll.bridge-enabled",havingValue="true")
public class PayrollInternalController {
    public record Actor(String username,String employeeId,String role) {}
    public record Person(String employeeId,String employeeName,String employeeCode,
                         String role,String status) {}
    public record Work(String scheduleId,LocalDate workDate,LocalTime startTime,LocalTime endTime,
                       String sourceType,String workName,BigDecimal hourlyRate) {}
    public record Facts(Person person,List<Work> works) {}
    public record HistoricItem(String id,String scheduleId,LocalDate workDate,String sourceType,
                               String workName,BigDecimal hours,BigDecimal rate,BigDecimal amount) {}
    public record HistoricPayroll(String id,String employeeId,String employeeName,String employeeCode,
                                  String roleId,String period,String source,int totalSessions,
                                  BigDecimal totalHours,BigDecimal rate,BigDecimal totalAmount,
                                  String status,Instant calculatedAt,List<HistoricItem> items) {}

    private final JdbcTemplate db;
    private final byte[] secret;

    public PayrollInternalController(JdbcTemplate db,
            @Value("${iig.payroll.internal-key}") String key) {
        if(key==null || key.isBlank()) {
            throw new IllegalArgumentException("PAYROLL_INTERNAL_API_KEY required");
        }
        this.db=db;
        this.secret=key.getBytes(StandardCharsets.UTF_8);
    }
    private void requireKey(String received) {
        byte[] value=received==null?new byte[0]:received.getBytes(StandardCharsets.UTF_8);
        if(!MessageDigest.isEqual(secret,value)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Payroll key");
        }
    }

    @GetMapping("/actor")
    public Actor actor(@RequestHeader(value="X-Internal-Key",required=false) String key,
                       Authentication authentication) {
        requireKey(key);
        if(authentication==null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Active session required");
        }
        List<Actor> rows=db.query(
            "SELECT u.username,e.employee_id,u.role_id FROM users u " +
            "LEFT JOIN employees e ON e.username=u.username AND e.status='ACTIVE' " +
            "WHERE u.username=? AND u.status='ACTIVE'",
            (rs,i)->new Actor(rs.getString(1),rs.getString(2),rs.getString(3)),
            authentication.getName());
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account inactive");
        return rows.get(0);
    }

    @GetMapping("/people")
    public List<Person> people(@RequestHeader(value="X-Internal-Key",required=false) String key) {
        requireKey(key);
        return db.query(
            "SELECT e.employee_id,e.full_name,e.employee_code,u.role_id,e.status " +
            "FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE u.role_id IN ('TEACHER','SALE','CS') AND u.status='ACTIVE' " +
            "ORDER BY e.employee_id",
            (rs,i)->new Person(rs.getString(1),rs.getString(2),rs.getString(3),
                               rs.getString(4),rs.getString(5)));
    }

    @GetMapping("/facts")
    public List<Facts> facts(@RequestHeader(value="X-Internal-Key",required=false) String key,
                             @RequestParam String period) {
        requireKey(key);
        final YearMonth ym=parsePeriod(period);
        List<Facts> rows=new ArrayList<>();
        for(Person person:people(key)) {
            if(!"ACTIVE".equals(person.status())) continue;
            List<Work> works;
            if("TEACHER".equals(person.role())) {
                works=db.query(
                    "SELECT t.teaching_schedule_id,t.date,t.start_time,t.end_time,c.name " +
                    "FROM teaching_schedules t " +
                    "JOIN teacher_session_attendance a ON a.teaching_schedule_id=t.teaching_schedule_id " +
                    "AND a.status IN ('PRESENT','LATE') " +
                    "JOIN classes c ON c.class_id=t.class_id " +
                    "WHERE t.teacher_id=? AND t.status='ASSIGNED' AND t.date BETWEEN ? AND ? " +
                    "ORDER BY t.date,t.teaching_schedule_id",
                    (rs,i)->new Work(rs.getString(1),rs.getObject(2,LocalDate.class),
                        rs.getObject(3,LocalTime.class),rs.getObject(4,LocalTime.class),
                        "TEACHING",rs.getString(5),
                        rateOn(person.employeeId(),rs.getObject(2,LocalDate.class))),
                    person.employeeId(),ym.atDay(1),ym.atEndOfMonth());
            } else {
                works=db.query(
                    "SELECT staff_schedule_id,date,start_time,end_time," +
                    "COALESCE(work_type,'Ca làm việc') FROM staff_schedules " +
                    "WHERE employee_id=? AND status='ASSIGNED' AND date BETWEEN ? AND ? " +
                    "ORDER BY date,staff_schedule_id",
                    (rs,i)->new Work(rs.getString(1),rs.getObject(2,LocalDate.class),
                        rs.getObject(3,LocalTime.class),rs.getObject(4,LocalTime.class),
                        "STAFF",rs.getString(5),
                        rateOn(person.employeeId(),rs.getObject(2,LocalDate.class))),
                    person.employeeId(),ym.atDay(1),ym.atEndOfMonth());
            }
            rows.add(new Facts(person,works));
        }
        return rows;
    }

    private BigDecimal rateOn(String employeeId,LocalDate workDate) {
        List<BigDecimal> history=db.query(
            "SELECT amount FROM salary_rank_history WHERE employee_id=? " +
            "AND effective_at::date<=? ORDER BY effective_at DESC,created_at DESC LIMIT 1",
            (rs,i)->rs.getBigDecimal(1),employeeId,workDate);
        if(!history.isEmpty()) return history.get(0);
        List<BigDecimal> fallback=db.query(
            "SELECT COALESCE((SELECT r.amount FROM employees e " +
            "JOIN salary_ranks r ON r.salary_rank_id=e.salary_rank_id WHERE e.employee_id=?)," +
            "(SELECT r.amount FROM employees e JOIN users u ON u.username=e.username " +
            "JOIN salary_ranks r ON r.role_id=u.role_id AND r.status='ACTIVE' " +
            "WHERE e.employee_id=? ORDER BY r.amount LIMIT 1))",
            (rs,i)->rs.getBigDecimal(1),employeeId,employeeId);
        return fallback.isEmpty() || fallback.get(0)==null ? BigDecimal.ZERO : fallback.get(0);
    }

    @GetMapping("/export")
    public List<HistoricPayroll> exportHistorical(
            @RequestHeader(value="X-Internal-Key",required=false) String key) {
        requireKey(key);
        String common="SELECT e.employee_id,e.full_name,e.employee_code,u.role_id ";
        String employee=common+
            ",s.employee_salary_id AS id,s.period,'EMPLOYEE' AS source,s.total_sessions AS total_count," +
            "s.total_hours,s.rate,s.amount,s.status,s.created_at " +
            "FROM employee_salaries s JOIN employees e ON e.employee_id=s.employee_id " +
            "JOIN users u ON u.username=e.username";
        String staff=common+
            ",s.staff_salary_id AS id,s.period,'STAFF' AS source,s.total_shifts AS total_count," +
            "s.total_hours,s.rate,s.amount,s.status,s.created_at " +
            "FROM staff_salaries s JOIN employees e ON e.employee_id=s.employee_id " +
            "JOIN users u ON u.username=e.username";
        List<HistoricPayroll> records=db.query(employee+" UNION ALL "+staff+" ORDER BY period,id",
            (rs,i)->{
                String id=rs.getString("id");
                String source=rs.getString("source");
                List<HistoricItem> items=db.query(
                    "SELECT item_id,schedule_id,work_date,work_type,worked_hours," +
                    "hourly_rate,amount FROM salary_calculation_items " +
                    "WHERE payroll_source=? AND payroll_id=? ORDER BY work_date,item_id",
                    (entry,j)->new HistoricItem(entry.getString(1),entry.getString(2),
                        entry.getObject(3,LocalDate.class),entry.getString(4),
                        entry.getString(4),entry.getBigDecimal(5),entry.getBigDecimal(6),
                        entry.getBigDecimal(7)),source,id);
                return new HistoricPayroll(id,rs.getString("employee_id"),
                    rs.getString("full_name"),rs.getString("employee_code"),
                    rs.getString("role_id"),rs.getString("period"),source,
                    rs.getInt("total_count"),rs.getBigDecimal("total_hours"),
                    rs.getBigDecimal("rate"),rs.getBigDecimal("amount"),
                    rs.getString("status"),rs.getTimestamp("created_at").toInstant(),items);
            });
        return records;
    }

    private YearMonth parsePeriod(String period) {
        try {
            if(period==null || !period.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException();
            return YearMonth.parse(period);
        } catch(Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"period must use YYYY-MM");
        }
    }
}

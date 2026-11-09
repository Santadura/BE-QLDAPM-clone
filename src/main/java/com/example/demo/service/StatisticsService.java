package com.example.demo.service;

import com.example.demo.dto.StatisticsDtos.EmployeeBreakdown;
import com.example.demo.dto.StatisticsDtos.FinancialSummary;
import com.example.demo.dto.StatisticsDtos.MonthlyStatistics;
import com.example.demo.dto.StatisticsDtos.Overview;
import com.example.demo.dto.StatisticsDtos.ScheduleSummary;
import com.example.demo.dto.StatisticsDtos.StatisticsResponse;
import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatisticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate db;

    public StatisticsResponse getStatistics(String requestedPeriod, Integer requestedYear, Integer requestedMonth,
                                            LocalDate requestedDate) {
        requireAdmin();
        return calculateStatistics(requestedPeriod, requestedYear, requestedMonth, requestedDate);
    }

    /**
     * Only use behind the protected, non-public internal Reporting API.
     * Reporting validates an ADMIN JWT against current core state itself.
     */
    public StatisticsResponse internalStatistics(String period, Integer year, Integer month, LocalDate date) {
        return calculateStatistics(period, year, month, date);
    }

    private StatisticsResponse calculateStatistics(String requestedPeriod, Integer requestedYear,
                                                    Integer requestedMonth, LocalDate requestedDate) {
        String period = requestedPeriod.toUpperCase(Locale.ROOT);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        int year = requestedYear == null ? today.getYear() : requestedYear;
        if (year < 1 || year > 9999) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Year must be between 1 and 9999");
        }

        LocalDate startDate;
        LocalDate endDate;
        switch (period) {
            case "DAY" -> {
                startDate = requestedDate == null ? today : requestedDate;
                endDate = startDate;
                year = startDate.getYear();
            }
            case "MONTH" -> {
                int month = requestedMonth == null ? today.getMonthValue() : requestedMonth;
                if (month < 1 || month > 12) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Month must be between 1 and 12");
                }
                YearMonth selectedMonth = YearMonth.of(year, month);
                startDate = selectedMonth.atDay(1);
                endDate = selectedMonth.atEndOfMonth();
            }
            case "YEAR" -> {
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.of(year, 12, 31);
            }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Period must be DAY, MONTH, or YEAR");
        }

        Overview overview = new Overview(
                count("SELECT count(*) FROM students WHERE upper(status)='ACTIVE'"),
                countStudentsCreatedBetween(startDate, endDate),
                count("SELECT count(*) FROM classes WHERE upper(status) IN ('READY','RUNNING') " +
                        "AND CAST(? AS date) BETWEEN start_date AND end_date", today));
        EmployeeBreakdown employees = employeeBreakdown();
        ScheduleSummary schedules = scheduleSummary(startDate, endDate);
        FinancialSummary financial = financialSummary(period, startDate, endDate);

        return new StatisticsResponse(
                period,
                startDate,
                endDate,
                overview,
                employees,
                schedules,
                financial,
                monthlyStatistics(year)
        );
    }

    private void requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = db.query(
                "SELECT upper(role_id) FROM users WHERE username=? AND upper(status)='ACTIVE'",
                rs -> rs.next() ? rs.getString(1) : null,
                authentication.getName());
        if (!"ADMIN".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Statistics are available to ADMIN only");
        }
    }

    private EmployeeBreakdown employeeBreakdown() {
        Map<String, Long> counts = new HashMap<>();
        db.query(
                "SELECT upper(u.role_id), count(*) FROM employees e " +
                        "JOIN users u ON u.username=e.username " +
                        "WHERE upper(e.status)='ACTIVE' AND upper(u.status)='ACTIVE' " +
                        "AND upper(u.role_id) IN ('TEACHER','TC','CM','SALE','CS','ADMIN') " +
                        "GROUP BY upper(u.role_id)",
                (RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));

        long teachers = counts.getOrDefault("TEACHER", 0L);
        long coordinators = counts.getOrDefault("TC", 0L);
        long centerManagers = counts.getOrDefault("CM", 0L);
        long sales = counts.getOrDefault("SALE", 0L);
        long customerSupport = counts.getOrDefault("CS", 0L);
        long admins = counts.getOrDefault("ADMIN", 0L);
        return new EmployeeBreakdown(teachers + coordinators + centerManagers + sales + customerSupport + admins,
                teachers, coordinators, centerManagers, sales, customerSupport, admins);
    }

    private ScheduleSummary scheduleSummary(LocalDate startDate, LocalDate endDate) {
        long teachingAssigned = countScheduleStatus("teaching_schedules", startDate, endDate, "ASSIGNED");
        long teachingPending = countScheduleStatus("teaching_schedules", startDate, endDate, "PENDING");
        long teachingCancelled = countScheduleStatus("teaching_schedules", startDate, endDate, "CANCELLED", "CANCELED");
        long staffAssigned = countScheduleStatus("staff_schedules", startDate, endDate, "ASSIGNED");
        long staffPending = countScheduleStatus("staff_schedules", startDate, endDate, "PENDING");
        long staffCancelled = countScheduleStatus("staff_schedules", startDate, endDate, "CANCELLED", "CANCELED");
        long pendingAvailability = count(
                "SELECT count(*) FROM availabilities WHERE upper(status)='PENDING' " +
                        "AND (available_date IS NULL OR available_date BETWEEN ? AND ?)",
                startDate, endDate);
        long teachingConflicts = count(
                "SELECT count(*) FROM teaching_schedules a JOIN teaching_schedules b " +
                        "ON a.teacher_id=b.teacher_id AND a.date=b.date " +
                        "AND a.teaching_schedule_id < b.teaching_schedule_id " +
                        "AND a.start_time < b.end_time AND a.end_time > b.start_time " +
                        "WHERE upper(a.status)='ASSIGNED' AND upper(b.status)='ASSIGNED' " +
                        "AND a.date BETWEEN ? AND ?",
                startDate, endDate);
        long staffConflicts = count(
                "SELECT count(*) FROM staff_schedules a JOIN staff_schedules b " +
                        "ON a.employee_id=b.employee_id AND a.date=b.date " +
                        "AND a.staff_schedule_id < b.staff_schedule_id " +
                        "AND a.start_time < b.end_time AND a.end_time > b.start_time " +
                        "WHERE upper(a.status)='ASSIGNED' AND upper(b.status)='ASSIGNED' " +
                        "AND a.date BETWEEN ? AND ?",
                startDate, endDate);
        return new ScheduleSummary(teachingAssigned, teachingPending, teachingCancelled,
                staffAssigned, staffPending, staffCancelled, pendingAvailability,
                teachingConflicts, staffConflicts);
    }

    private long countScheduleStatus(String table, LocalDate startDate, LocalDate endDate, String... statuses) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        List<Object> args = new ArrayList<>(List.of(startDate, endDate));
        args.addAll(List.of(statuses));
        return count("SELECT count(*) FROM " + table + " WHERE date BETWEEN ? AND ? " +
                "AND upper(status) IN (" + placeholders + ")", args.toArray());
    }

    private FinancialSummary financialSummary(String period, LocalDate startDate, LocalDate endDate) {
        if ("DAY".equals(period)) {
            return new FinancialSummary(null, false, null, null);
        }
        String firstPeriod = YearMonth.from(startDate).toString();
        String lastPeriod = YearMonth.from(endDate).toString();
        BigDecimal salaryCost = sumPayroll(firstPeriod, lastPeriod);
        long payrollRecords = countPayroll(firstPeriod, lastPeriod);
        return new FinancialSummary(null, false, salaryCost, payrollRecords);
    }

    private List<MonthlyStatistics> monthlyStatistics(int year) {
        String firstPeriod = YearMonth.of(year, 1).toString();
        String lastPeriod = YearMonth.of(year, 12).toString();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 1);
        return db.query(
                "SELECT months.month_start::date, " +
                        "(SELECT count(*) FROM students s WHERE " +
                        "s.created_at < ((months.month_start + interval '1 month')::timestamp " +
                        "AT TIME ZONE 'Asia/Ho_Chi_Minh')), " +
                        "(SELECT count(*) FROM students s WHERE " +
                        "s.created_at >= (months.month_start::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh') " +
                        "AND s.created_at < ((months.month_start + interval '1 month')::timestamp " +
                        "AT TIME ZONE 'Asia/Ho_Chi_Minh')), " +
                        "(SELECT count(*) FROM classes c WHERE upper(c.status) IN ('READY','RUNNING','COMPLETED','CLOSED') " +
                        "AND c.start_date <= (months.month_start + interval '1 month' - interval '1 day')::date " +
                        "AND c.end_date >= months.month_start::date), " +
                        "COALESCE(payroll.amount, 0) " +
                        "FROM generate_series(CAST(? AS date), CAST(? AS date), interval '1 month') " +
                        "AS months(month_start) LEFT JOIN (" +
                        "SELECT period, sum(amount) AS amount FROM (" +
                        "SELECT period, amount FROM employee_salaries " +
                        "UNION ALL SELECT period, amount FROM staff_salaries" +
                        ") salary_records WHERE period BETWEEN ? AND ? GROUP BY period" +
                        ") payroll ON payroll.period=to_char(months.month_start, 'YYYY-MM') " +
                        "ORDER BY months.month_start",
                (rs, rowNum) -> {
                    LocalDate monthStart = rs.getDate(1).toLocalDate();
                    int month = monthStart.getMonthValue();
                    return new MonthlyStatistics(month, "T" + month, rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), null, rs.getBigDecimal(5), null);
                },
                yearStart, yearEnd, firstPeriod, lastPeriod);
    }

    private long countStudentsCreatedBetween(LocalDate startDate, LocalDate endDate) {
        return count(
                "SELECT count(*) FROM students WHERE created_at >= " +
                        "(CAST(? AS date)::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh') " +
                        "AND created_at < ((CAST(? AS date) + interval '1 day')::timestamp " +
                        "AT TIME ZONE 'Asia/Ho_Chi_Minh')",
                startDate, endDate);
    }

    private BigDecimal sumPayroll(String firstPeriod, String lastPeriod) {
        BigDecimal value = db.queryForObject(
                "SELECT COALESCE(sum(payroll.amount), 0) FROM (" +
                        "SELECT period, amount FROM employee_salaries " +
                        "UNION ALL SELECT period, amount FROM staff_salaries" +
                        ") payroll WHERE payroll.period BETWEEN ? AND ?",
                BigDecimal.class, firstPeriod, lastPeriod);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long countPayroll(String firstPeriod, String lastPeriod) {
        return count(
                "SELECT count(*) FROM (" +
                        "SELECT period FROM employee_salaries " +
                        "UNION ALL SELECT period FROM staff_salaries" +
                        ") payroll WHERE payroll.period BETWEEN ? AND ?",
                firstPeriod, lastPeriod);
    }

    private long count(String sql, Object... args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}

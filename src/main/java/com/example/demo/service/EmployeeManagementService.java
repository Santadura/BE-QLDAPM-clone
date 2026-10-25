package com.example.demo.service;

import com.example.demo.dto.EmployeeDtos.*;
import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeManagementService {
    private final JdbcTemplate jdbc;
    private static final Set<String> STAFF = Set.of("ADMIN", "TC", "CM", "TEACHER", "SALE", "CS");
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("ACTIVE", "INACTIVE", "ON_LEAVE");
    private static final String SUMMARY = """
        SELECT e.employee_id AS "employeeId", e.employee_code AS "employeeCode", e.username,
               e.full_name AS "fullName", e.phone, e.specialization, e.status,
               u.email, u.role_id AS role, u.status AS "accountStatus",
               e.salary_rank_id AS "salaryRankId", r.rank AS "salaryRank", r.amount AS "rankAmount",
               to_char(e.reviewed_date, 'YYYY-MM-DD') AS "reviewedDate",
               to_char(e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI') AS "createdAt"
        FROM employees e JOIN users u ON u.username=e.username
        LEFT JOIN salary_ranks r ON r.salary_rank_id=e.salary_rank_id
        """;
    private static final String CERTIFICATES = """
        SELECT certificate_id AS "certificateId", certificate_type AS "certificateType", score,
               score_sw AS "scoreSW", to_char(issue_date, 'YYYY-MM-DD') AS "issueDate",
               to_char(expiry_date, 'YYYY-MM-DD') AS "expiryDate", issuer,
               certificate_url AS "certificateUrl", status
        FROM certificates
        """;

    private List<String> scope(Authentication actor) {
        if (actor == null || !actor.isAuthenticated()) fail(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        var roles = jdbc.queryForList("SELECT role_id FROM users WHERE username=? AND status='ACTIVE'", String.class, actor.getName());
        if (roles.isEmpty()) { fail(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ."); }
        String role = roles.get(0);
        if (actor.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_" + role))) {
            fail(HttpStatus.FORBIDDEN, "Không có quyền quản lý nhân viên.");
        }
        if ("ADMIN".equals(role)) return new ArrayList<>(STAFF);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM employees WHERE username=? AND status='ACTIVE'", Integer.class, actor.getName());
        if (active == null || active == 0) fail(HttpStatus.FORBIDDEN, "Hồ sơ quản lý không hoạt động.");
        return switch (role) {
            case "TC" -> List.of("TEACHER");
            case "CM" -> List.of("SALE", "CS");
            default -> throw new ApiException(HttpStatus.FORBIDDEN, "Không có quyền quản lý nhân viên.");
        };
    }

    public List<Map<String, Object>> list(LocalDate weekStart, String search, String role, String status, Authentication actor) {
        List<String> allowed = scope(actor);
        String query = optional(search);
        String requestedRole = optional(role);
        String requestedStatus = optional(status);
        if (query != null && query.length() > 200) fail(HttpStatus.BAD_REQUEST, "Search must not exceed 200 characters.");
        if (requestedRole != null) {
            requestedRole = requestedRole.toUpperCase(Locale.ROOT);
            if (!STAFF.contains(requestedRole)) fail(HttpStatus.BAD_REQUEST, "Unsupported employee role.");
            if (!allowed.contains(requestedRole)) fail(HttpStatus.FORBIDDEN, "Employee role is outside your management scope.");
        }
        if (requestedStatus != null) {
            requestedStatus = requestedStatus.toUpperCase(Locale.ROOT);
            if (!EMPLOYMENT_STATUSES.contains(requestedStatus)) fail(HttpStatus.BAD_REQUEST, "Unsupported employment status.");
        }
        StringBuilder sql = new StringBuilder(SUMMARY).append(" WHERE u.role_id IN (")
                .append(placeholders(allowed.size())).append(")");
        List<Object> parameters = new ArrayList<>(allowed);
        if (requestedRole != null) { sql.append(" AND u.role_id=?"); parameters.add(requestedRole); }
        if (requestedStatus != null) { sql.append(" AND e.status=?"); parameters.add(requestedStatus); }
        if (query != null) {
            // Literal substring matching: user-supplied % and _ are not wildcards.
            sql.append(" AND position(lower(?) in lower(concat_ws(' ', e.full_name, e.employee_code, e.username, u.email)))>0");
            parameters.add(query);
        }
        sql.append(" ORDER BY e.created_at DESC, e.employee_code");
        LocalDate start = monday(weekStart);
        var rows = jdbc.queryForList(sql.toString(), parameters.toArray());
        if (rows.isEmpty()) return rows;
        var workloads = jdbc.queryForList("""
            SELECT employee_id AS id, SUM(EXTRACT(EPOCH FROM (end_time-start_time))/3600) AS hours
            FROM (
                SELECT teacher_id AS employee_id, date, start_time, end_time, status FROM teaching_schedules
                UNION ALL
                SELECT employee_id, date, start_time, end_time, status FROM staff_schedules
            ) s WHERE date>=? AND date<? AND upper(status) IN ('ASSIGNED', 'COMPLETED') GROUP BY employee_id
            """, start, start.plusDays(7));
        Map<String, Object> hours = new HashMap<>();
        for (var row : workloads) hours.put((String) row.get("id"), row.get("hours"));
        for (var row : rows) row.put("weeklyHours", hours.getOrDefault(row.get("employeeId"), 0));
        return rows;
    }

    public Map<String, Object> detail(String id, Authentication actor) {
        List<String> allowed = scope(actor);
        var rows = jdbc.queryForList(SUMMARY + " WHERE e.employee_id=?", id);
        if (rows.isEmpty()) fail(HttpStatus.NOT_FOUND, "Không tìm thấy nhân viên.");
        var employee = rows.get(0);
        if (!allowed.contains(employee.get("role"))) fail(HttpStatus.FORBIDDEN, "Nhân viên nằm ngoài phạm vi quản lý.");
        return employee;
    }

    public List<Map<String, Object>> ranks(Authentication actor) {
        List<String> allowed = scope(actor);
        return jdbc.queryForList("SELECT salary_rank_id AS \"salaryRankId\", role_id AS role, rank, amount, status FROM salary_ranks WHERE status='ACTIVE' AND role_id IN ("
                + placeholders(allowed.size()) + ") ORDER BY role_id, amount, rank", allowed.toArray());
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateEmployeeRequest request, Authentication actor) {
        lockWrites();
        var employee = detail(id, actor);
        String username = (String) employee.get("username");
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Integer duplicates = jdbc.queryForObject("SELECT count(*) FROM users WHERE (lower(email)=lower(?) AND username<>?) OR lower(username)=lower(?)",
                Integer.class, email, username, email);
        if (duplicates != null && duplicates > 0) fail(HttpStatus.CONFLICT, "Email đã được sử dụng.");
        String rank = optional(request.salaryRankId());
        boolean changedRank = !Objects.equals(rank, employee.get("salaryRankId"));
        if (rank != null && changedRank) {
            Integer valid = jdbc.queryForObject("SELECT count(*) FROM salary_ranks WHERE salary_rank_id=? AND role_id=? AND status='ACTIVE'",
                    Integer.class, rank, employee.get("role"));
            if (valid == null || valid == 0) fail(HttpStatus.BAD_REQUEST, "Bậc lương phải đang hoạt động và đúng vai trò nhân viên.");
        }
        jdbc.update("UPDATE users SET email=? WHERE username=?", email, username);
        jdbc.update("UPDATE employees SET full_name=?, phone=?, specialization=?, salary_rank_id=?, "
                + "reviewed_date=CASE WHEN ? THEN now() ELSE reviewed_date END WHERE employee_id=?",
                request.fullName().trim().replaceAll("\\s+", " "), optional(request.phone()), optional(request.specialization()),
                rank, changedRank, id);
        audit(actor, id, "UPDATE_EMPLOYEE", "Updated employee profile");
        return detail(id, actor);
    }

    @Transactional
    public Map<String, Object> changeStatus(String id, String status, Authentication actor) {
        lockWrites();
        var employee = detail(id, actor);
        if (status == null || !EMPLOYMENT_STATUSES.contains(status)) fail(HttpStatus.BAD_REQUEST, "Unsupported employment status.");
        String username = (String) employee.get("username");
        if ("INACTIVE".equals(status) && username.equals(actor.getName())) {
            fail(HttpStatus.CONFLICT, "You cannot deactivate your own employee profile or lock your current account.");
        }
        String previous = (String) employee.get("status");
        boolean changed = !status.equals(previous);
        boolean locked = "INACTIVE".equals(status) && !"LOCKED".equals(employee.get("accountStatus"));
        if (changed) jdbc.update("UPDATE employees SET status=? WHERE employee_id=?", status, id);
        // Employment and login status remain distinct. Ending employment locks
        // login in this transaction; reactivation never unlocks a user account.
        if (locked) {
            jdbc.update("UPDATE users SET status='LOCKED' WHERE username=?", username);
            jdbc.update("INSERT INTO audit_logs (audit_log_id, username, action, entity_type, entity_id, description) "
                            + "VALUES (?,?,'CHANGE_ACCOUNT_STATUS','ACCOUNT',?,?)",
                    UUID.randomUUID().toString(), actor.getName(), username,
                    employee.get("accountStatus") + " -> LOCKED (employee deactivated)");
        }
        if (changed || locked) audit(actor, id, "INACTIVE".equals(status) ? "DEACTIVATE_EMPLOYEE" : "CHANGE_EMPLOYEE_STATUS",
                previous + " -> " + status + (locked ? "; account locked" : ""));
        // No related schedules, salary records or certificates are deleted.
        return detail(id, actor);
    }

    public List<Map<String, Object>> schedules(String id, LocalDate weekStart, Authentication actor) {
        detail(id, actor);
        LocalDate start = monday(weekStart);
        return jdbc.queryForList("""
            SELECT s.id, s.type, to_char(s.date, 'YYYY-MM-DD') AS date,
                   to_char(s.start_time, 'HH24:MI') AS "startTime", to_char(s.end_time, 'HH24:MI') AS "endTime",
                   s.status, c.name AS "className", c.class_code AS "classCode", s.workplace
            FROM (
                SELECT teaching_schedule_id AS id, 'TEACHING' AS type, date, start_time, end_time, status, class_id, NULL::varchar AS workplace
                FROM teaching_schedules WHERE teacher_id=?
                UNION ALL
                SELECT staff_schedule_id, work_type, date, start_time, end_time, status, class_id, workplace_id
                FROM staff_schedules WHERE employee_id=?
            ) s LEFT JOIN classes c ON c.class_id=s.class_id
            WHERE s.date>=? AND s.date<? ORDER BY s.date, s.start_time, s.id
            """, id, id, start, start.plusDays(7));
    }

    public List<Map<String, Object>> salaries(String id, Authentication actor) {
        detail(id, actor);
        return jdbc.queryForList("""
            SELECT 'EMPLOYEE' AS source, s.employee_salary_id AS id, s.period, s.total_sessions AS units,
                   s.total_hours AS "totalHours", s.rate, s.amount, s.status,
                   d.salary_per_hours AS "detailRate", d.total_salary AS "detailAmount"
            FROM employee_salaries s LEFT JOIN employee_salary_details d ON d.employee_salary_id=s.employee_salary_id
            WHERE s.employee_id=?
            UNION ALL
            SELECT 'STAFF', staff_salary_id, period, total_shifts, total_hours, rate, amount, status, NULL, NULL
            FROM staff_salaries WHERE employee_id=? ORDER BY period DESC, source, id
            """, id, id);
    }

    public List<Map<String, Object>> availabilities(String id, LocalDate weekStart, Authentication actor) {
        detail(id, actor);
        LocalDate start = monday(weekStart);
        return jdbc.queryForList("""
            SELECT availability_id AS id, to_char(available_date, 'YYYY-MM-DD') AS date,
                   day_of_week AS day, to_char(start_time, 'HH24:MI') AS "startTime",
                   to_char(end_time, 'HH24:MI') AS "endTime", shift, campus, status, note
            FROM availabilities WHERE employee_id=? AND
                ((available_date>=? AND available_date<?) OR (available_date IS NULL AND day_of_week IS NOT NULL))
            ORDER BY available_date NULLS LAST, day_of_week, start_time
            """, id, start, start.plusDays(7));
    }

    public List<Map<String, Object>> history(String id, Authentication actor) {
        var employee = detail(id, actor);
        return jdbc.queryForList("""
            SELECT audit_log_id AS id, username, action, description,
                   to_char(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI') AS "createdAt"
            FROM audit_logs WHERE (entity_type='EMPLOYEE' AND entity_id=?)
               OR (entity_type='ACCOUNT' AND entity_id=? AND action IN ('CREATE_ACCOUNT', 'UPDATE_PROFILE', 'CHANGE_ACCOUNT_STATUS'))
            ORDER BY created_at DESC, audit_log_id LIMIT 100
            """, id, employee.get("username"));
    }

    public List<Map<String, Object>> certificates(String id, Authentication actor) {
        detail(id, actor);
        return jdbc.queryForList(CERTIFICATES + " WHERE employee_id=? ORDER BY created_at DESC, certificate_id", id);
    }

    @Transactional
    public Map<String, Object> saveCertificate(String id, String certificateId, CertificateRequest request, Authentication actor) {
        lockWrites();
        detail(id, actor);
        if ((request.score() != null && !Double.isFinite(request.score()))
                || (request.scoreSW() != null && !Double.isFinite(request.scoreSW()))) {
            fail(HttpStatus.BAD_REQUEST, "Điểm chứng chỉ không hợp lệ.");
        }
        if (request.issueDate() != null && request.expiryDate() != null && request.expiryDate().isBefore(request.issueDate())) {
            fail(HttpStatus.BAD_REQUEST, "Ngày hết hạn không được trước ngày cấp.");
        }
        String url = optional(request.certificateUrl());
        if (url != null) {
            try {
                URI uri = URI.create(url);
                if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) { fail(HttpStatus.BAD_REQUEST, "Đường dẫn chứng chỉ phải là URL HTTP hoặc HTTPS hợp lệ."); }
        }
        String key = certificateId == null ? UUID.randomUUID().toString() : certificateId;
        if (certificateId == null) {
            jdbc.update("INSERT INTO certificates (certificate_id, employee_id, certificate_type, score, score_sw, issue_date, expiry_date, issuer, certificate_url, status) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    key, id, request.certificateType().trim(), request.score(), request.scoreSW(), request.issueDate(), request.expiryDate(), optional(request.issuer()), url, request.status());
        } else {
            int updated = jdbc.update("UPDATE certificates SET certificate_type=?, score=?, score_sw=?, issue_date=?, expiry_date=?, issuer=?, certificate_url=?, status=? WHERE certificate_id=? AND employee_id=?",
                    request.certificateType().trim(), request.score(), request.scoreSW(), request.issueDate(), request.expiryDate(), optional(request.issuer()), url, request.status(), key, id);
            if (updated == 0) fail(HttpStatus.NOT_FOUND, "Không tìm thấy chứng chỉ của nhân viên.");
        }
        audit(actor, id, certificateId == null ? "CREATE_CERTIFICATE" : "UPDATE_CERTIFICATE", "Cập nhật chứng chỉ: " + request.certificateType().trim());
        return jdbc.queryForMap(CERTIFICATES + " WHERE certificate_id=? AND employee_id=?", key, id);
    }

    private void lockWrites() {
        // Share the lock used by account/profile writes; leave all identifiers and roles unchanged.
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(724190231)", Integer.class);
    }

    private void audit(Authentication actor, String id, String action, String description) {
        jdbc.update("INSERT INTO audit_logs (audit_log_id, username, action, entity_type, entity_id, description) VALUES (?,?,?,'EMPLOYEE',?,?)",
                UUID.randomUUID().toString(), actor.getName(), action, id, description);
    }

    private LocalDate monday(LocalDate date) {
        return (date == null ? LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")) : date).with(DayOfWeek.MONDAY);
    }
    private String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }
    private String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void fail(HttpStatus status, String message) { throw new ApiException(status, message); }
}

package com.example.demo.service;

import com.example.demo.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SchedulingAccess {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;

    public record StaffIdentity(String id, String username, String role) {}

    public StaffIdentity current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        var employees = jdbc.query(
            "SELECT e.employee_id, e.username, u.role_id FROM employees e " +
            "JOIN users u ON e.username=u.username " +
            "WHERE e.username=? AND e.status='ACTIVE' AND u.status='ACTIVE'",
            (rs, row) -> new StaffIdentity(rs.getString(1), rs.getString(2), rs.getString(3)),
            authentication.getName());
        if (employees.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Active employee profile required");
        }
        return employees.get(0);
    }

    public StaffIdentity requireRole(String... roles) {
        StaffIdentity actor = current();
        if (Arrays.stream(roles).noneMatch(role -> role.equalsIgnoreCase(actor.role()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Role is not allowed for this action");
        }
        return actor;
    }

    /**
     * Authorizes read-only management views from the active user account.
     *
     * An ADMIN account is allowed to supervise schedules even when it is not
     * represented in {@code employees}.  Do not use this for operations that
     * create, change, approve, or audit employee-owned data: those actions
     * must continue to use {@link #requireRole(String...)} and therefore have
     * an active employee profile.
     */
    public StaffIdentity requireActiveRole(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        var users = jdbc.query(
            "SELECT username, role_id FROM users WHERE username=? AND status='ACTIVE'",
            (rs, row) -> new StaffIdentity(null, rs.getString(1), rs.getString(2)),
            authentication.getName());
        if (users.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Active user account required");
        }

        StaffIdentity actor = users.get(0);
        if (Arrays.stream(roles).noneMatch(role -> role.equalsIgnoreCase(actor.role()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Role is not allowed for this action");
        }
        return actor;
    }

    public void requireEmployeeRole(String employeeId, String role) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM employees e JOIN users u ON u.username=e.username " +
            "WHERE e.employee_id=? AND e.status='ACTIVE' AND u.status='ACTIVE' AND u.role_id=?",
            Integer.class, employeeId, role);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Employee not found or has incorrect role");
        }
    }

    public void validTimes(LocalTime start, LocalTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End time must be later than start time");
        }
    }

    public LocalDate monday(LocalDate weekStart) {
        LocalDate date = weekStart == null ? LocalDate.now(BUSINESS_ZONE) : weekStart;
        return date.with(DayOfWeek.MONDAY);
    }

    public LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    public String day(LocalDate date) {
        return date.getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
    }

    public String normalizedDay(String input) {
        if (input == null) return null;
        String day = input.trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("mon", "tue", "wed", "thu", "fri", "sat", "sun").contains(day)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid dayOfWeek");
        }
        return day;
    }

    public void lockEmployee(String employeeId) {
        jdbc.queryForObject("SELECT employee_id FROM employees WHERE employee_id=? FOR UPDATE",
            String.class, employeeId);
    }

    public void audit(StaffIdentity actor, String action, String type, String entityId) {
        jdbc.update("INSERT INTO audit_logs (audit_log_id,username,action,entity_type,entity_id) " +
            "VALUES (?,?,?,?,?)", UUID.randomUUID().toString(), actor.username(),
            action, type, entityId);
    }

    public boolean hasApprovedAvailability(String employeeId, LocalDate date,
                                            LocalTime start, LocalTime end, String campus) {
        // RECURRING applies to the same weekday, including past lessons.
        // WEEKLY is valid only for its exact available_date.
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM availabilities WHERE employee_id=? " +
            "AND upper(btrim(status))='APPROVED' AND start_time<=? AND end_time>=? " +
            "AND (available_date=? OR (available_date IS NULL AND lower(btrim(day_of_week))=?)) " +
            "AND (? IS NULL OR campus IS NULL OR btrim(campus)='' " +
            "OR lower(btrim(campus))=lower(btrim(?)))",
            Integer.class, employeeId, start, end, date, day(date), campus, campus);
        return count != null && count > 0;
    }

    /**
     * Give TC/CM the failed constraint, rather than a misleading generic
     * "outside availability" message. This is diagnostic only: it never approves
     * availability or relaxes the weekday, date, time or campus requirements.
     */
    public String availabilityMismatchReason(String employeeId, LocalDate date,
                                             LocalTime start, LocalTime end, String campus) {
        List<Map<String,Object>> slots = jdbc.queryForList(
            "SELECT available_date, day_of_week, availability_type, start_time, end_time, " +
            "status, campus FROM availabilities WHERE employee_id=? " +
            "ORDER BY CASE WHEN available_date=? OR " +
            "(available_date IS NULL AND lower(btrim(day_of_week))=?) THEN 0 ELSE 1 END, " +
            "reviewed_at DESC NULLS LAST LIMIT 50",
            employeeId, date, day(date));
        if (slots.isEmpty()) {
            return "Nhân sự " + employeeId + " chưa có bản ghi lịch rảnh trong database";
        }
        List<Map<String,Object>> onDay = slots.stream().filter(slot ->
            slot.get("available_date") != null
                ? date.toString().equals(String.valueOf(slot.get("available_date")))
                : day(date).equalsIgnoreCase(String.valueOf(slot.get("day_of_week")).trim())
        ).toList();
        if (onDay.isEmpty()) {
            String samples = slots.stream().limit(3).map(slot -> {
                Object specific = slot.get("available_date");
                return specific == null
                    ? "định kỳ " + slot.get("day_of_week") + " " +
                      slot.get("start_time") + "-" + slot.get("end_time")
                    : "theo ngày " + specific + " " +
                      slot.get("start_time") + "-" + slot.get("end_time");
            }).collect(java.util.stream.Collectors.joining(", "));
            return "Không có lịch rảnh áp dụng ngày " + date + " (thứ " +
                    day(date) + ") cho nhân sự " + employeeId +
                    "; lịch tìm thấy: " + samples;
        }
        List<Map<String,Object>> approved = onDay.stream().filter(slot ->
            "APPROVED".equalsIgnoreCase(String.valueOf(slot.get("status")).trim())
        ).toList();
        if (approved.isEmpty()) {
            return "Lịch rảnh ngày " + date + " của " + employeeId +
                " chưa được duyệt (trạng thái: " + onDay.stream()
                    .map(slot -> String.valueOf(slot.get("status")))
                    .distinct().collect(java.util.stream.Collectors.joining(", ")) + ")";
        }
        List<Map<String,Object>> covering = approved.stream().filter(slot ->
            !java.time.LocalTime.parse(String.valueOf(slot.get("start_time"))).isAfter(start)
            && !java.time.LocalTime.parse(String.valueOf(slot.get("end_time"))).isBefore(end)
        ).toList();
        if (covering.isEmpty()) {
            return "Ngày " + date + " giáo viên đã có lịch rảnh APPROVED, nhưng không bao phủ " +
                start + "-" + end + "; các giờ được duyệt: " +
                approved.stream().map(slot -> slot.get("start_time") + "-" + slot.get("end_time"))
                    .distinct().collect(java.util.stream.Collectors.joining(", "));
        }
        String normalizedCampus = campus == null ? "" : campus.trim();
        String availableCampuses = covering.stream()
            .map(slot -> slot.get("campus") == null ? "" : String.valueOf(slot.get("campus")).trim())
            .distinct().collect(java.util.stream.Collectors.joining(", "));
        if (!normalizedCampus.isEmpty() && covering.stream().noneMatch(slot ->
            slot.get("campus") == null || String.valueOf(slot.get("campus")).isBlank() ||
            normalizedCampus.equalsIgnoreCase(String.valueOf(slot.get("campus")).trim())
        )) {
            return "Giáo viên đã rảnh đúng ngày/giờ nhưng khác cơ sở: đang chọn '" +
                campus + "', lịch rảnh tại '" + availableCampuses + "'";
        }
        return "Có lịch rảnh phù hợp nhưng truy vấn xác thực không nhận diện được; hãy kiểm tra dữ liệu availabilities của " + employeeId;
    }

    public String campusForWorkplace(String workplaceId) {
        if (workplaceId == null) return null;
        return switch (workplaceId) {
            case "center-01" -> "Cơ sở chính";
            case "center-02" -> "Cơ sở Quận 1";
            case "center-03" -> "Cơ sở Quận 3";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown workplaceId");
        };
    }

    public Map<String, Object> employeeSummary(String employeeId) {
        return jdbc.queryForMap(
            "SELECT e.employee_id AS id, e.employee_id AS \"employeeId\", " +
            "e.full_name AS \"fullName\", e.employee_code AS \"employeeCode\", " +
            "u.role_id AS role FROM employees e JOIN users u ON e.username=u.username WHERE e.employee_id=?",
            employeeId);
    }
}

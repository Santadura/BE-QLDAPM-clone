package com.example.demo.service;

import com.example.demo.dto.AccountDtos.*;
import com.example.demo.dto.ProfileDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.example.demo.entity.AuditLog;
import com.example.demo.entity.Employee;
import com.example.demo.entity.User;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.AuditLogRepository;
import com.example.demo.repository.EmployeeRepository;
import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountManagementService {
    private static final Set<String> STAFF_ROLES = Set.of("ADMIN", "TC", "CM", "TEACHER", "SALE", "CS");
    private static final Map<String, String> CODE_PREFIXES = Map.of(
            "ADMIN", "ADMIN-", "TC", "TC-", "CM", "CM-",
            "TEACHER", "GV-", "SALE", "SALE-", "CS", "CS-");
    private final UserRepository users;
    private final EmployeeRepository employees;
    private final AuditLogRepository auditLogs;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Authentication actor) {
        requireAdmin(actor);
        Map<String, Employee> profiles = employees.findAll().stream()
                .collect(Collectors.toMap(Employee::getUsername, Function.identity()));
        return users.findAllByRoleIdInOrderByCreatedAtDescUsernameAsc(STAFF_ROLES).stream()
                .map(user -> response(user, profiles.get(user.getUsername()))).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse detail(String username, Authentication actor) {
        requireAdmin(actor);
        return response(requireAccount(username), employees.findByUsername(username).orElse(null));
    }

    public AccountResponse create(CreateAccountRequest request, Authentication actor) {
        requireAdmin(actor);
        if (!STAFF_ROLES.contains(request.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ.");
        }
        // Serialize all generated identifiers across application instances.
        // Transaction commit/rollback releases the lock; no schema change is needed.
        lockAccountWrites();
        requireAdmin(actor);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email) || users.existsByUsernameIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email đã được tài khoản khác sử dụng.");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }
        String username = nextIdentifier(request.role().toLowerCase(Locale.ROOT),
                jdbc.queryForList("SELECT username FROM users UNION SELECT email FROM users UNION "
                        + "SELECT entity_id FROM audit_logs WHERE entity_type IN ('ACCOUNT', 'USERNAME')", String.class), 50);
        String employeeId = nextEmployeeId();
        String code = nextEmployeeCode(request.role());
        User user = users.saveAndFlush(User.builder()
                .username(username).email(email).passwordHash(passwords.encode(request.password()))
                .roleId(request.role()).status("ACTIVE").createdAt(Instant.now()).build());
        Employee employee = employees.saveAndFlush(Employee.builder()
                .employeeId(employeeId).username(username).employeeCode(code)
                .fullName(request.fullName().trim().replaceAll("\\s+", " "))
                .phone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim())
                .status("ACTIVE").build());
        // Remember previously issued values so manual deletions do not cause reuse.
        audit(actor, "RESERVE_ACCOUNT_IDENTIFIER", "USERNAME", username, "Reserved username " + username);
        audit(actor, "RESERVE_ACCOUNT_IDENTIFIER", "EMPLOYEE_ID", employeeId, "Reserved employee ID for " + username);
        audit(actor, "RESERVE_ACCOUNT_IDENTIFIER", "EMPLOYEE_CODE", code, "Reserved employee code for " + username);
        audit(actor, "CREATE_ACCOUNT", "ACCOUNT", username, "Created staff account with fixed role " + request.role());
        return response(user, employee);
    }

    public AccountResponse changeStatus(String username, String status, Authentication actor) {
        requireAdmin(actor);
        lockAccountWrites();
        if (!Set.of("ACTIVE", "LOCKED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Trạng thái không hợp lệ.");
        }
        User user = requireAccount(username);
        if ("LOCKED".equals(status) && actor.getName().equalsIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "Bạn không thể tự khóa tài khoản đang đăng nhập.");
        }
        String previous = user.getStatus();
        if (!previous.equals(status)) {
            user.setStatus(status);
            users.save(user);
            audit(actor, "CHANGE_ACCOUNT_STATUS", "ACCOUNT", username, previous + " -> " + status);
        }
        // Login state and employment state are independent in the existing schema.
        return response(user, employees.findByUsername(username).orElse(null));
    }

    @Transactional(readOnly = true)
    public AccountResponse profile(Authentication actor) {
        User user = requireCurrentUser(actor, false);
        return response(user, employees.findByUsername(user.getUsername()).orElse(null));
    }

    @Transactional(readOnly = true)
    public CurrentSalaryResponse profileSalary(Authentication actor) {
        User user = requireCurrentUser(actor, false);
        // Resolve identity from authentication only; never accept a target username.
        List<CurrentSalaryResponse> rows = jdbc.query("""
                SELECT r.salary_rank_id, r.rank, r.amount,
                       to_char(e.reviewed_date AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD') AS reviewed_date
                FROM employees e LEFT JOIN salary_ranks r ON r.salary_rank_id=e.salary_rank_id
                WHERE e.username=?
                """, (rs, index) -> {
            String reviewedDate = rs.getString("reviewed_date");
            return new CurrentSalaryResponse(rs.getString("salary_rank_id"), rs.getString("rank"),
                    rs.getBigDecimal("amount"), reviewedDate == null ? null : LocalDate.parse(reviewedDate));
        }, user.getUsername());
        return rows.isEmpty() ? new CurrentSalaryResponse(null, null, null, null) : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> profileCertificates(Authentication actor) {
        User user = requireCurrentUser(actor, false);
        return jdbc.queryForList("""
                SELECT c.certificate_id AS "certificateId", c.certificate_type AS "certificateType",
                       c.score, c.score_sw AS "scoreSW", to_char(c.issue_date, 'YYYY-MM-DD') AS "issueDate",
                       to_char(c.expiry_date, 'YYYY-MM-DD') AS "expiryDate", c.issuer,
                       c.certificate_url AS "certificateUrl", c.status
                FROM certificates c JOIN employees e ON e.employee_id=c.employee_id
                WHERE e.username=? ORDER BY c.created_at DESC, c.certificate_id
                """, user.getUsername());
    }

    public AccountResponse updateProfile(UpdateProfileRequest request, Authentication actor) {
        lockAccountWrites();
        User user = requireCurrentUser(actor, true);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCaseAndUsernameNot(email, user.getUsername())
                || users.existsByUsernameIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email đã được tài khoản khác sử dụng.");
        }
        String fullName = request.fullName().trim().replaceAll("\\s+", " ");
        String phone = request.phone() == null || request.phone().isBlank() ? null : request.phone().trim();
        String specialization = request.specialization() == null || request.specialization().isBlank()
                ? null : request.specialization().trim();
        user.setEmail(email);
        users.saveAndFlush(user);

        Employee employee = employees.findByUsername(user.getUsername()).orElse(null);
        if (employee == null) {
            // Some seeded accounts (Admin) have no Employee row yet. Persist
            // their own profile on first save using the existing allocation rules.
            String employeeId = nextEmployeeId();
            String code = nextEmployeeCode(user.getRoleId());
            employee = Employee.builder().employeeId(employeeId).username(user.getUsername())
                    .employeeCode(code).fullName(fullName).phone(phone).specialization(specialization).status("ACTIVE").build();
            employee = employees.saveAndFlush(employee);
            audit(actor, "RESERVE_ACCOUNT_IDENTIFIER", "EMPLOYEE_ID", employeeId,
                    "Reserved employee ID for " + user.getUsername());
            audit(actor, "RESERVE_ACCOUNT_IDENTIFIER", "EMPLOYEE_CODE", code,
                    "Reserved employee code for " + user.getUsername());
        } else {
            employee.setFullName(fullName);
            employee.setPhone(phone);
            employee.setSpecialization(specialization);
            employees.saveAndFlush(employee);
        }
        audit(actor, "UPDATE_PROFILE", "ACCOUNT", user.getUsername(), "Updated own profile");
        return response(user, employee);
    }

    public void changePassword(ChangePasswordRequest request, Authentication actor) {
        lockAccountWrites();
        User user = requireCurrentUser(actor, true);
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không chính xác.");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu xác nhận không khớp.");
        }
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }
        if (passwords.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }
        user.setPasswordHash(passwords.encode(request.newPassword()));
        users.saveAndFlush(user);
        audit(actor, "CHANGE_PASSWORD", "ACCOUNT", user.getUsername(), "Changed own password");
    }

    private User requireCurrentUser(Authentication actor, boolean forUpdate) {
        if (actor == null || !actor.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        User user = users.findByUsername(actor.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không còn hợp lệ."));
        if (forUpdate) {
            // The security filter may have loaded this User before waiting for
            // the transaction lock. Refresh its data before checking a password
            // or persisting fields, and hold the row lock until commit.
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản đã bị khóa.");
        }
        if (!STAFF_ROLES.contains(user.getRoleId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ tài khoản nhân viên được sử dụng chức năng này.");
        }
        return user;
    }

    private void lockAccountWrites() {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(724190231)", Integer.class);
    }

    private String nextEmployeeId() {
        return nextIdentifier("EMP-", jdbc.queryForList(
                "SELECT employee_id FROM employees UNION SELECT entity_id FROM audit_logs WHERE entity_type = 'EMPLOYEE_ID'", String.class), 50);
    }

    private String nextEmployeeCode(String role) {
        return nextIdentifier(CODE_PREFIXES.get(role), jdbc.queryForList(
                "SELECT employee_code FROM employees UNION SELECT entity_id FROM audit_logs WHERE entity_type = 'EMPLOYEE_CODE'", String.class), 30);
    }

    private String nextIdentifier(String prefix, List<String> existing, int maxLength) {
        BigInteger maximum = BigInteger.ZERO;
        for (String value : existing) {
            if (value == null || !value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) continue;
            String suffix = value.substring(prefix.length());
            if (suffix.matches("[0-9]+")) maximum = maximum.max(new BigInteger(suffix));
        }
        String number = maximum.add(BigInteger.ONE).toString();
        String identifier = prefix + "0".repeat(Math.max(0, 3 - number.length())) + number;
        if (identifier.length() > maxLength) {
            throw new ApiException(HttpStatus.CONFLICT, "Không thể cấp thêm định danh cho tài khoản nhân viên.");
        }
        return identifier;
    }

    private User requireAccount(String username) {
        return users.findByUsername(username).filter(user -> STAFF_ROLES.contains(user.getRoleId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản nhân viên."));
    }

    private void requireAdmin(Authentication actor) {
        if (actor == null || !actor.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        boolean authority = actor.getAuthorities().stream().anyMatch(item -> "ROLE_ADMIN".equals(item.getAuthority()));
        boolean activeAdmin = users.findByUsername(actor.getName())
                .filter(user -> "ADMIN".equals(user.getRoleId()) && "ACTIVE".equals(user.getStatus())).isPresent();
        if (!authority || !activeAdmin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Chỉ Admin đang hoạt động được quản lý tài khoản.");
        }
    }

    private AccountResponse response(User user, Employee employee) {
        return new AccountResponse(user.getUsername(), user.getEmail(),
                employee == null ? user.getUsername() : employee.getFullName(),
                employee == null ? null : employee.getEmployeeId(),
                employee == null ? null : employee.getEmployeeCode(),
                employee == null ? null : employee.getPhone(), user.getRoleId(), user.getStatus(), user.getCreatedAt(),
                employee == null ? null : employee.getSpecialization());
    }

    private void audit(Authentication actor, String action, String entityType, String entityId, String description) {
        auditLogs.save(AuditLog.builder().auditLogId("audit-" + UUID.randomUUID())
                .username(actor.getName()).action(action).entityType(entityType).entityId(entityId)
                .description(description).createdAt(Instant.now()).build());
    }
}

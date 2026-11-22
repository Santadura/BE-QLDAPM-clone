package com.example.demo.service;

import com.example.demo.dto.AuthDtos.AuthResponse;
import com.example.demo.dto.AuthDtos.LoginRequest;
import com.example.demo.dto.AuthDtos.RegisterRequest;
import com.example.demo.dto.AuthDtos.CurrentAccountResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.example.demo.entity.User;
import com.example.demo.entity.AuditLog;
import com.example.demo.repository.AuditLogRepository;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final AuditLogRepository auditLogs;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        // Dùng chung khóa với luồng tạo tài khoản nhân viên để tránh cấp trùng.
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(724190231)", Integer.class);
        String username = req.username().trim().toLowerCase(Locale.ROOT);
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByUsernameIgnoreCase(username) || userRepository.existsByEmailIgnoreCase(username)) {
            throw new ApiException(HttpStatus.CONFLICT, "Tên đăng nhập đã được sử dụng.");
        }
        if (userRepository.existsByEmailIgnoreCase(email) || userRepository.existsByUsernameIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
        }
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8.");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .roleId("USER")
                .status("ACTIVE")
                .build();

        userRepository.saveAndFlush(user);

        String token = jwtUtil.generateToken(user.getUsername(), user.getRoleId(), user.getPasswordHash());
        return new AuthResponse(token, user.getUsername(), user.getRoleId());
    }

    @Transactional(readOnly = true)
    public CurrentAccountResponse currentAccount(Authentication actor) {
        if (actor == null || !actor.isAuthenticated()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        User user = userRepository.findByUsername(actor.getName())
                .filter(item -> "ACTIVE".equals(item.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ."));
        return new CurrentAccountResponse(user.getUsername(), user.getEmail(), user.getRoleId(), user.getStatus(), user.getDisplayName(), user.getPhone(), user.getContactEmail(), user.getCreatedAt());
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sai username hoac password"));
        // Keep the credential checked during login stable until token issuance.
        // A concurrent password change must finish before or after this login.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_READ);
        if (!user.isPasswordLoginEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Tài khoản này sử dụng đăng nhập Google hoặc Facebook.");
        }
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password())
        );
        String token = jwtUtil.generateToken(user.getUsername(), user.getRoleId(), user.getPasswordHash());
        return new AuthResponse(token, user.getUsername(), user.getRoleId());
    }

    @Transactional
    public void logout(String authorization, Authentication actor) {
        if (actor == null || !actor.isAuthenticated() || authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        }
        final String tokenId;
        try {
            String token = authorization.substring(7);
            if (!actor.getName().equals(jwtUtil.extractUsername(token))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ.");
            }
            tokenId = jwtUtil.extractTokenId(token);
            if (tokenId == null || tokenId.isBlank() || tokenId.length() > 50) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ.");
            }
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không còn hợp lệ.");
        }
        if (!auditLogs.existsByEntityTypeAndEntityId("JWT_SESSION", tokenId)) {
            auditLogs.saveAndFlush(AuditLog.builder().auditLogId("audit-" + UUID.randomUUID())
                    .username(actor.getName()).action("LOGOUT").entityType("JWT_SESSION").entityId(tokenId)
                    .description("User signed out; JWT session revoked").createdAt(Instant.now()).build());
        }
    }
}

package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public class AuthDtos {

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "[a-zA-Z0-9_]+", message = "Tên đăng nhập chỉ gồm chữ không dấu, số và dấu gạch dưới.") String username,
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(min = 6, max = 72) String password
    ) {}

    public record CurrentAccountResponse(String username, String email, String role, String status) {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    public record AuthResponse(
            String token,
            String tokenType,
            String username,
            String role
    ) {
        public AuthResponse(String token, String username, String role) {
            this(token, "Bearer", username, role);
        }
    }
}

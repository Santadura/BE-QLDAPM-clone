package com.example.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public class AccountDtos {
    public static final String STAFF_ROLES = "ADMIN|TC|CM|TEACHER|SALE|CS";

    public record CreateAccountRequest(
            @NotBlank @Email @Size(max = 100) String email,
            @NotBlank @Size(min = 6, max = 72) String password,
            @NotBlank @Size(max = 100) String fullName,
            @Size(max = 15) @Pattern(regexp = "\\+?[0-9]{9,15}|", message = "Phone must contain 9 to 15 digits") String phone,
            @NotBlank @Pattern(regexp = STAFF_ROLES, message = "Unsupported employee role") String role
    ) {}

    public record ChangeStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|LOCKED", message = "Status must be ACTIVE or LOCKED") String status
    ) {}

    public record AccountResponse(
            String username, String email, String fullName, String employeeId,
            String employeeCode, String phone, String role, String status, Instant createdAt, String specialization
    ) {}
}

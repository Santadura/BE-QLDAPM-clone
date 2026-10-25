package com.example.demo.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public class EmployeeDtos {
    public record UpdateEmployeeRequest(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Email @Size(max = 100) String email,
            @Size(max = 15) @Pattern(regexp = "\\+?[0-9]{9,15}|") String phone,
            @Size(max = 100) String specialization,
            @Size(max = 50) String salaryRankId
    ) {}

    public record ChangeEmployeeStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|ON_LEAVE", message = "Unsupported employment status") String status
    ) {}

    public record CertificateRequest(
            @NotBlank @Size(max = 30) String certificateType,
            @PositiveOrZero Double score,
            @PositiveOrZero Double scoreSW,
            LocalDate issueDate,
            LocalDate expiryDate,
            @Size(max = 100) String issuer,
            @Size(max = 500) String certificateUrl,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status
    ) {}
}

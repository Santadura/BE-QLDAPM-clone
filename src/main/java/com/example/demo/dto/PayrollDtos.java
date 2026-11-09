package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class PayrollDtos {
    private PayrollDtos() {}
    public record SalaryRankRequest(@NotBlank String roleId, @NotBlank String rank,
                                    @NotNull @PositiveOrZero BigDecimal amount, String status) {}
    public record SalaryRankUpdate(@NotBlank String rank, @NotNull @PositiveOrZero BigDecimal amount, String status) {}
    public record AssignSalaryRank(@NotBlank String salaryRankId, LocalDate effectiveDate, String note) {}
    public record ReviewRequest(@NotBlank String employeeId, @NotBlank String proposedSalaryRankId,
                                @NotBlank String reason) {}
    public record ReviewDecision(@NotBlank String decision, String note) {}
}

package vn.iig.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Explicit version-1 HTTP contracts from the transitional Core. Payroll owns
 * its ledger and computations; Core only supplies people and observed work.
 */
public final class PayrollContracts {
    private PayrollContracts() {}

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
    public record PayrollRow(String id,String period,String employeeId,
                             String employeeName,String employeeCode,String roleId,
                             int totalSessions,BigDecimal totalHours,BigDecimal totalAmount,
                             Instant calculatedAt,String source) {}
    public record PayrollItem(String id,LocalDate workDate,String sourceType,String workName,
                              BigDecimal hours,BigDecimal rate,BigDecimal amount) {}
    public record PayrollDetail(String id,String period,String employeeId,
                                String employeeName,String employeeCode,String roleId,
                                int totalSessions,BigDecimal totalHours,BigDecimal totalAmount,
                                Instant calculatedAt,String source,List<PayrollItem> items) {}
}

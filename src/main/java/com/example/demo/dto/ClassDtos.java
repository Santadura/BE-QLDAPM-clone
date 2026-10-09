package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public final class ClassDtos {
    private ClassDtos() {}

    public record TargetRequirementInput(
            @NotBlank String targetType,
            @NotNull BigDecimal requiredTarget
    ) {}

    public record ClassUpsertRequest(
            @NotBlank String courseId,
            @NotBlank @Size(max = 30) String classCode,
            @NotBlank @Size(max = 150) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Valid @NotNull List<TargetRequirementInput> targetRequirements
    ) {}

    public record ChangeStatusRequest(@NotBlank String status) {}

    public record AddStudentsRequest(@NotNull List<String> studentIds) {}

    public record AssignmentUpsertRequest(
            @NotBlank @Size(max = 150) String title,
            String description,
            @NotNull LocalDate deadline
    ) {}

    public record AssignmentStatusRequest(@NotBlank String status) {}

    public record ExamUpsertRequest(
            @NotBlank @Size(max = 150) String title,
            String description,
            @NotNull Integer duration,
            @NotNull LocalDate examDate
    ) {}

    public record ExamStatusRequest(@NotBlank String status) {}

    public record StudentResultUpsertRequest(
            @NotBlank String studentId,
            String assignmentId,
            String examId,
            @NotNull BigDecimal score,
            String feedback
    ) {}

    public record SupportOverrideRequest(
            @NotBlank String newCsId,
            @NotBlank String reason,
            boolean allowConflict
    ) {}

    public record CourseSummary(
            String courseId,
            String name
    ) {}

    public record TargetRequirementResponse(
            String id,
            String targetType,
            BigDecimal requiredTarget
    ) {}

    public record StudentSummary(
            String studentId,
            String studentCode,
            String fullName,
            String phone,
            String email,
            String status,
            String membershipStatus,
            Instant joinedAt
    ) {}

    public record TeachingScheduleResponse(
            String id,
            String teacherId,
            String teacherName,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String status
    ) {}

    public record StaffScheduleResponse(
            String id,
            String employeeId,
            String employeeName,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            String workType,
            String status,
            String assignmentSource
    ) {}

    public record AssignmentResponse(
            String id,
            String teacherId,
            String title,
            String description,
            Instant deadline,
            String status,
            Instant createdAt
    ) {}

    public record ExamResponse(
            String id,
            String teacherId,
            String title,
            String description,
            Integer duration,
            Instant examDate,
            String status,
            Instant createdAt
    ) {}

    public record StudentResultResponse(
            String id,
            String studentId,
            String assignmentId,
            String examId,
            BigDecimal score,
            String feedback,
            String evaluatedBy,
            Instant evaluatedAt
    ) {}

    public record AuditLogResponse(
            String id,
            String username,
            String action,
            String description,
            Instant createdAt
    ) {}

    public record ClassSummaryResponse(
            String classId,
            String courseId,
            String courseName,
            String classCode,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String createdBy,
            Instant createdAt,
            long studentCount,
            List<String> teacherNames
    ) {}

    public record ClassDetailResponse(
            String classId,
            CourseSummary course,
            String classCode,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String createdBy,
            Instant createdAt,
            List<TargetRequirementResponse> targetRequirements,
            List<StudentSummary> students,
            List<TeachingScheduleResponse> teachingSchedules,
            List<StaffScheduleResponse> staffSchedules,
            List<AssignmentResponse> assignments,
            List<ExamResponse> exams,
            List<StudentResultResponse> results,
            List<AuditLogResponse> auditLogs
    ) {}

    public record RosterChangeResponse(
            int affected,
            List<String> studentIds
    ) {}

    public record EligibilityResponse(
            boolean eligible,
            String code,
            List<String> reasons,
            Map<String, BigDecimal> requiredTargets,
            Map<String, BigDecimal> studentTargets
    ) {}
}

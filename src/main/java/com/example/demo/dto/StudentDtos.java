package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class StudentDtos {
    private StudentDtos() {}

    public record StudentUpsertRequest(
            @NotBlank @Size(max = 30) String studentCode,
            @NotBlank @Size(max = 100) String fullName,
            @Size(max = 100) String email,
            @Size(max = 15) String phone,
            @Size(max = 30) String level,
            Map<String, Map<String, BigDecimal>> targets
    ) {}

    public record ChangeStudentStatusRequest(@NotBlank String status) {}

    public record StudentTargetResponse(
            String id,
            String courseId,
            String targetType,
            BigDecimal targetValue
    ) {}

    public record StudentClassResponse(
            String membershipId,
            String classId,
            String classCode,
            String className,
            String classStatus,
            String membershipStatus,
            Instant joinedAt,
            String inactiveReason,
            Instant inactivatedAt
    ) {}

    public record StudentResultResponse(
            String id,
            String classId,
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

    public record StudentSummaryResponse(
            String studentId,
            String studentCode,
            String fullName,
            String email,
            String phone,
            String level,
            String status
    ) {}

    public record StudentDetailResponse(
            String studentId,
            String studentCode,
            String fullName,
            String email,
            String phone,
            String level,
            String status,
            List<StudentTargetResponse> targets,
            List<StudentClassResponse> classes,
            List<StudentResultResponse> results,
            List<AuditLogResponse> auditLogs
    ) {}

    public record StatusChangeResponse(
            StudentDetailResponse student,
            List<String> deactivatedClassIds
    ) {}
}

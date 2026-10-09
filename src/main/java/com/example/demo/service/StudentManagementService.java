package com.example.demo.service;

import com.example.demo.dto.StudentDtos.*;
import com.example.demo.entity.*;
import com.example.demo.exception.ApiException;
import com.example.demo.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentManagementService {

    private static final Set<String> ASSIGNABLE_CLASS_STATUSES = Set.of("DRAFT", "READY", "RUNNING");
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            "Active", Set.of("On Leave", "Graduated"),
            "On Leave", Set.of("Active", "Graduated"),
            "Graduated", Set.of("Active")
    );

    private final StudentRepository studentRepository;
    private final StudentTargetRepository studentTargetRepository;
    private final ClassStudentRepository classStudentRepository;
    private final AcademicClassRepository classRepository;
    private final TeachingScheduleRepository teachingScheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final ClassTargetRequirementRepository classTargetRequirementRepository;
    private final StudentResultRepository studentResultRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<StudentSummaryResponse> listStudents(Authentication authentication) {
        Actor actor = actor(authentication);
        List<Student> students;

        if ("ADMIN".equals(actor.role())) {
            students = studentRepository.findAllByOrderByFullNameAsc();
        } else if ("TEACHER".equals(actor.role())) {
            Employee teacher = requireEmployee(actor.username());
            Set<String> classIds = teachingScheduleRepository
                    .findByTeacherIdAndStatusIgnoreCase(teacher.getEmployeeId(), "ASSIGNED")
                    .stream()
                    .map(TeachingSchedule::getClassId)
                    .collect(Collectors.toSet());

            if (classIds.isEmpty()) {
                students = List.of();
            } else {
                Set<String> studentIds = classStudentRepository
                        .findByClassIdInAndStatusIgnoreCase(classIds, "ACTIVE")
                        .stream()
                        .map(ClassStudent::getStudentId)
                        .collect(Collectors.toSet());
                students = studentRepository.findAllByOrderByFullNameAsc().stream()
                        .filter(student -> studentIds.contains(student.getStudentId()))
                        .toList();
            }
        } else {
            students = List.of();
        }

        return students.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse getStudent(String studentId, Authentication authentication) {
        Student student = requireStudent(studentId);
        Actor actor = actor(authentication);

        Set<String> allowedClassIds = null;
        Set<String> allowedCourseIds = null;
        if ("ADMIN".equals(actor.role())) {
            // Admin sees all student data.
        } else if ("TEACHER".equals(actor.role())) {
            Employee teacher = requireEmployee(actor.username());
            Set<String> teacherClassIds = teachingScheduleRepository
                    .findByTeacherIdAndStatusIgnoreCase(teacher.getEmployeeId(), "ASSIGNED")
                    .stream()
                    .map(TeachingSchedule::getClassId)
                    .collect(Collectors.toSet());
            allowedClassIds = teacherClassIds;

            boolean studentVisible = classStudentRepository.findByStudentId(studentId).stream()
                    .anyMatch(membership ->
                            "ACTIVE".equalsIgnoreCase(membership.getStatus())
                            && teacherClassIds.contains(membership.getClassId()));
            if (!studentVisible) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Teacher can only view students in assigned classes");
            }

            allowedCourseIds = classRepository.findAllById(teacherClassIds).stream()
                    .map(AcademicClass::getCourseId)
                    .collect(Collectors.toSet());
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "You do not have permission to view student records");
        }

        final Set<String> classScope = allowedClassIds;
        final Set<String> courseScope = allowedCourseIds;

        List<StudentTargetResponse> targets = studentTargetRepository.findByStudentId(studentId).stream()
                .filter(target -> courseScope == null || courseScope.contains(target.getCourseId()))
                .map(target -> new StudentTargetResponse(
                        target.getStudentTargetId(),
                        target.getCourseId(),
                        target.getTargetType(),
                        target.getTargetValue()))
                .toList();

        List<ClassStudent> memberships = classStudentRepository.findByStudentId(studentId).stream()
                .filter(membership -> classScope == null || classScope.contains(membership.getClassId()))
                .toList();
        Map<String, AcademicClass> classes = classRepository.findAllById(
                memberships.stream().map(ClassStudent::getClassId).toList()
        ).stream().collect(Collectors.toMap(AcademicClass::getClassId, Function.identity()));

        List<StudentClassResponse> classResponses = memberships.stream()
                .map(membership -> {
                    AcademicClass item = classes.get(membership.getClassId());
                    if (item == null) return null;
                    return new StudentClassResponse(
                            membership.getClassStudentId(),
                            item.getClassId(),
                            item.getClassCode(),
                            item.getName(),
                            item.getStatus(),
                            membership.getStatus(),
                            membership.getJoinedAt(),
                            membership.getInactiveReason(),
                            membership.getInactivatedAt());
                })
                .filter(Objects::nonNull)
                .toList();

        List<StudentResultResponse> results = studentResultRepository
                .findByStudentIdOrderByEvaluatedAtDesc(studentId)
                .stream()
                .filter(result -> classScope == null || classScope.contains(result.getClassId()))
                .map(result -> new StudentResultResponse(
                        result.getStudentResultId(),
                        result.getClassId(),
                        result.getAssignmentId(),
                        result.getExamId(),
                        result.getScore(),
                        result.getFeedback(),
                        result.getEvaluatedById(),
                        result.getEvaluatedAt()))
                .toList();

        List<AuditLogResponse> auditLogs = "ADMIN".equals(actor.role())
                ? auditLogRepository.findTop50ByEntityTypeIgnoreCaseAndEntityIdOrderByCreatedAtDesc("STUDENT", studentId)
                    .stream()
                    .map(log -> new AuditLogResponse(
                            log.getAuditLogId(), log.getUsername(), log.getAction(),
                            log.getDescription(), log.getCreatedAt()))
                    .toList()
                : List.of();

        return new StudentDetailResponse(
                student.getStudentId(),
                student.getStudentCode(),
                student.getFullName(),
                student.getEmail(),
                student.getPhone(),
                student.getLevel(),
                student.getStatus(),
                targets,
                classResponses,
                results,
                auditLogs
        );
    }

    @Transactional
    public StudentDetailResponse createStudent(
            StudentUpsertRequest request,
            Authentication authentication
    ) {
        Actor actor = requireAdmin(authentication);
        validateStudentRequest(request, null);

        Student student = Student.builder()
                .studentId(newId("student"))
                .studentCode(request.studentCode().trim())
                .fullName(request.fullName().trim())
                .email(trimToNull(request.email()))
                .phone(trimToNull(request.phone()))
                .level(trimToNull(request.level()))
                .status("Active")
                .build();
        studentRepository.save(student);
        replaceTargets(student.getStudentId(), normalizedTargets(request.targets()));

        audit(actor.username(), "CREATE_STUDENT", student.getStudentId(),
                "Created student " + student.getStudentCode());
        return getStudent(student.getStudentId(), authentication);
    }

    @Transactional
    public StudentDetailResponse updateStudent(
            String studentId,
            StudentUpsertRequest request,
            Authentication authentication
    ) {
        Actor actor = requireAdmin(authentication);
        Student student = requireStudent(studentId);
        validateStudentRequest(request, studentId);

        Map<String, Map<String, BigDecimal>> targets = normalizedTargets(request.targets());
        ensureActiveClassCompatibility(student, targets);

        student.setStudentCode(request.studentCode().trim());
        student.setFullName(request.fullName().trim());
        student.setEmail(trimToNull(request.email()));
        student.setPhone(trimToNull(request.phone()));
        student.setLevel(trimToNull(request.level()));
        studentRepository.save(student);
        replaceTargets(studentId, targets);

        audit(actor.username(), "UPDATE_STUDENT", studentId,
                "Updated student " + student.getStudentCode());
        return getStudent(studentId, authentication);
    }

    @Transactional
    public void deleteStudent(String studentId, Authentication authentication) {
        Actor actor = requireAdmin(authentication);
        Student student = requireStudent(studentId);

        if (!classStudentRepository.findByStudentId(studentId).isEmpty()
                || studentResultRepository.existsByStudentId(studentId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This student already has class or academic history. Change status instead of deleting");
        }

        List<StudentTarget> targets = studentTargetRepository.findByStudentId(studentId);
        if (!targets.isEmpty()) {
            studentTargetRepository.deleteAll(targets);
            studentTargetRepository.flush();
        }
        studentRepository.delete(student);
        studentRepository.flush();

        audit(actor.username(), "DELETE_STUDENT", studentId,
                "Deleted student " + student.getStudentCode());
    }

    @Transactional
    public StatusChangeResponse changeStatus(
            String studentId,
            ChangeStudentStatusRequest request,
            Authentication authentication
    ) {
        Actor actor = requireAdmin(authentication);
        Student student = requireStudent(studentId);
        String nextStatus = normalizeStatus(request.status());

        Set<String> allowed = STATUS_TRANSITIONS.getOrDefault(student.getStatus(), Set.of());
        if (!allowed.contains(nextStatus)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot change student status from " + student.getStatus() + " to " + nextStatus);
        }

        String previous = student.getStatus();
        student.setStatus(nextStatus);
        studentRepository.save(student);

        List<String> deactivatedClassIds = new ArrayList<>();
        if (!"Active".equals(nextStatus)) {
            Map<String, AcademicClass> classes = classRepository.findAllById(
                    classStudentRepository.findByStudentIdAndStatusIgnoreCase(studentId, "ACTIVE")
                            .stream().map(ClassStudent::getClassId).toList()
            ).stream().collect(Collectors.toMap(AcademicClass::getClassId, Function.identity()));

            for (ClassStudent membership :
                    classStudentRepository.findByStudentIdAndStatusIgnoreCase(studentId, "ACTIVE")) {
                AcademicClass classItem = classes.get(membership.getClassId());
                if (classItem != null && ASSIGNABLE_CLASS_STATUSES.contains(classItem.getStatus())) {
                    membership.setStatus("INACTIVE");
                    membership.setInactiveReason("STUDENT_" + nextStatus.toUpperCase().replace(" ", "_"));
                    membership.setInactivatedAt(Instant.now());
                    classStudentRepository.save(membership);
                    deactivatedClassIds.add(classItem.getClassId());

                    audit(actor.username(), "DEACTIVATE_STUDENT_MEMBERSHIP", classItem.getClassId(),
                            "Student " + studentId + " deactivated because status changed to " + nextStatus,
                            "CLASS");
                }
            }
        }

        audit(actor.username(), "CHANGE_STUDENT_STATUS", studentId,
                "Changed student status from " + previous + " to " + nextStatus);
        return new StatusChangeResponse(getStudent(studentId, authentication), deactivatedClassIds);
    }

    private void ensureActiveClassCompatibility(
            Student student,
            Map<String, Map<String, BigDecimal>> proposedTargets
    ) {
        List<ClassStudent> activeMemberships =
                classStudentRepository.findByStudentIdAndStatusIgnoreCase(student.getStudentId(), "ACTIVE");

        for (ClassStudent membership : activeMemberships) {
            AcademicClass classItem = classRepository.findById(membership.getClassId()).orElse(null);
            if (classItem == null || !ASSIGNABLE_CLASS_STATUSES.contains(classItem.getStatus())) {
                continue;
            }

            Map<String, BigDecimal> required = classTargetRequirementRepository
                    .findByClassIdOrderByTargetTypeAsc(classItem.getClassId())
                    .stream()
                    .collect(Collectors.toMap(
                            ClassTargetRequirement::getTargetType,
                            ClassTargetRequirement::getRequiredTarget));

            Map<String, BigDecimal> courseTargets =
                    proposedTargets.getOrDefault(classItem.getCourseId(), Map.of());

            for (Map.Entry<String, BigDecimal> requirement : required.entrySet()) {
                BigDecimal actual = courseTargets.get(requirement.getKey());
                if (actual == null || actual.compareTo(requirement.getValue()) < 0) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Target changes would make this student ineligible for active class "
                                    + classItem.getClassCode());
                }
            }
        }
    }

    private void validateStudentRequest(StudentUpsertRequest request, String currentStudentId) {
        String code = request.studentCode().trim();
        boolean duplicate = currentStudentId == null
                ? studentRepository.existsByStudentCodeIgnoreCase(code)
                : studentRepository.existsByStudentCodeIgnoreCaseAndStudentIdNot(code, currentStudentId);
        if (duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "Student code already exists");
        }

        Map<String, Map<String, BigDecimal>> targets = normalizedTargets(request.targets());
        for (Map.Entry<String, Map<String, BigDecimal>> entry : targets.entrySet()) {
            validateOptionalCourseTargets(entry.getKey(), entry.getValue());
        }
    }

    private void validateOptionalCourseTargets(String courseId, Map<String, BigDecimal> values) {
        TargetRule rule = targetRule(courseId);
        if (rule == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Student target contains an unsupported course");
        }
        if (values == null || values.isEmpty()) return;

        if (!values.keySet().equals(rule.types())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Configure all target fields for " + courseId + " or leave the course target blank");
        }

        for (String type : rule.types()) {
            BigDecimal value = values.get(type);
            TargetRange range = rule.ranges().get(type);
            validateRange(type, value, range.min(), range.max(), range.step());
        }
    }

    private TargetRule targetRule(String courseId) {
        return switch (courseId) {
            case "course-toeic" -> new TargetRule(
                    Set.of("LR_TOTAL", "SW_TOTAL"),
                    Map.of(
                            "LR_TOTAL", new TargetRange(new BigDecimal("10"), new BigDecimal("990"), new BigDecimal("5")),
                            "SW_TOTAL", new TargetRange(BigDecimal.ZERO, new BigDecimal("400"), new BigDecimal("10"))
                    ));
            case "course-ielts" -> new TargetRule(
                    Set.of("OVERALL_BAND"),
                    Map.of("OVERALL_BAND", new TargetRange(BigDecimal.ZERO, new BigDecimal("9"), new BigDecimal("0.5"))));
            case "course-sat" -> new TargetRule(
                    Set.of("TOTAL"),
                    Map.of("TOTAL", new TargetRange(new BigDecimal("400"), new BigDecimal("1600"), new BigDecimal("10"))));
            case "course-toefl" -> new TargetRule(
                    Set.of("OVERALL_1_6"),
                    Map.of("OVERALL_1_6", new TargetRange(BigDecimal.ONE, new BigDecimal("6"), new BigDecimal("0.5"))));
            default -> null;
        };
    }

    private void validateRange(
            String type,
            BigDecimal value,
            BigDecimal min,
            BigDecimal max,
            BigDecimal step
    ) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    type + " must be between " + min + " and " + max);
        }
        if (value.subtract(min).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    type + " must use step " + step);
        }
    }

    private void replaceTargets(
            String studentId,
            Map<String, Map<String, BigDecimal>> targets
    ) {
        List<StudentTarget> existing = studentTargetRepository.findByStudentId(studentId);
        if (!existing.isEmpty()) {
            studentTargetRepository.deleteAll(existing);
            studentTargetRepository.flush();
        }

        List<StudentTarget> replacements = new ArrayList<>();
        targets.forEach((courseId, values) -> values.forEach((targetType, targetValue) ->
                replacements.add(StudentTarget.builder()
                        .studentTargetId(newId("student-target"))
                        .studentId(studentId)
                        .courseId(courseId)
                        .targetType(targetType)
                        .targetValue(targetValue)
                        .build())
        ));
        studentTargetRepository.saveAll(replacements);
    }

    private Map<String, Map<String, BigDecimal>> normalizedTargets(
            Map<String, Map<String, BigDecimal>> targets
    ) {
        if (targets == null) return Map.of();

        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        targets.forEach((courseId, values) -> {
            if (courseId == null || courseId.isBlank()) return;
            Map<String, BigDecimal> normalizedValues = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((type, value) -> {
                    if (type != null && !type.isBlank() && value != null) {
                        normalizedValues.put(type.trim().toUpperCase(), value);
                    }
                });
            }
            if (!normalizedValues.isEmpty()) {
                result.put(courseId.trim(), normalizedValues);
            }
        });
        return result;
    }

    private StudentSummaryResponse toSummary(Student student) {
        return new StudentSummaryResponse(
                student.getStudentId(),
                student.getStudentCode(),
                student.getFullName(),
                student.getEmail(),
                student.getPhone(),
                student.getLevel(),
                student.getStatus());
    }

    private Student requireStudent(String studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Student not found"));
    }

    private Employee requireEmployee(String username) {
        return employeeRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "Authenticated user is not linked to an employee"));
    }

    private Actor requireAdmin(Authentication authentication) {
        Actor actor = actor(authentication);
        if (!"ADMIN".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only Admin can manage student records");
        }
        return actor;
    }

    private Actor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        String role = authentication.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5))
                .findFirst()
                .orElse("");
        return new Actor(authentication.getName(), role);
    }

    private void audit(String username, String action, String entityId, String description) {
        audit(username, action, entityId, description, "STUDENT");
    }

    private void audit(
            String username,
            String action,
            String entityId,
            String description,
            String entityType
    ) {
        auditLogRepository.save(AuditLog.builder()
                .auditLogId(newId("audit"))
                .username(username)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .description(description)
                .createdAt(Instant.now())
                .build());
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeStatus(String raw) {
        if (raw == null) return "";
        return switch (raw.trim().toLowerCase()) {
            case "active" -> "Active";
            case "on leave" -> "On Leave";
            case "graduated" -> "Graduated";
            default -> raw.trim();
        };
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private record Actor(String username, String role) {}
    private record TargetRange(BigDecimal min, BigDecimal max, BigDecimal step) {}
    private record TargetRule(Set<String> types, Map<String, TargetRange> ranges) {}
}

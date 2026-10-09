package com.example.demo.service;

import com.example.demo.dto.ClassDtos.*;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClassManagementService {

    private static final List<String> CLASS_STATUSES =
            List.of("DRAFT", "READY", "RUNNING", "COMPLETED", "CLOSED");
    private static final Set<String> ASSIGNABLE_STATUSES =
            Set.of("DRAFT", "READY", "RUNNING");

    private final AcademicClassRepository classRepository;
    private final CourseRepository courseRepository;
    private final EmployeeRepository employeeRepository;
    private final StudentRepository studentRepository;
    private final StudentTargetRepository studentTargetRepository;
    private final ClassTargetRequirementRepository targetRequirementRepository;
    private final ClassStudentRepository classStudentRepository;
    private final ClassAccessScopeRepository classAccessScopeRepository;
    private final TeachingScheduleRepository teachingScheduleRepository;
    private final StaffScheduleRepository staffScheduleRepository;
    private final AssignmentRepository assignmentRepository;
    private final ExamRepository examRepository;
    private final StudentResultRepository studentResultRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ClassSummaryResponse> listClasses(Authentication authentication) {
        Actor actor = actor(authentication);
        List<AcademicClass> classes = switch (actor.role()) {
            case "ADMIN" -> classRepository.findAllByOrderByCreatedAtDesc();
            case "CS" -> {
                Employee employee = requireEmployee(actor.username());
                Set<String> ids = classAccessScopeRepository
                        .findByEmployeeIdAndStatusIgnoreCase(employee.getEmployeeId(), "ACTIVE")
                        .stream()
                        .map(ClassAccessScope::getClassId)
                        .collect(Collectors.toSet());
                yield classRepository.findAllByOrderByCreatedAtDesc().stream()
                        .filter(item -> ids.contains(item.getClassId()))
                        .toList();
            }
            case "TEACHER" -> {
                Employee employee = requireEmployee(actor.username());
                Set<String> ids = teachingScheduleRepository
                        .findByTeacherIdAndStatusIgnoreCase(employee.getEmployeeId(), "ASSIGNED")
                        .stream()
                        .map(TeachingSchedule::getClassId)
                        .collect(Collectors.toSet());
                yield classRepository.findAllByOrderByCreatedAtDesc().stream()
                        .filter(item -> ids.contains(item.getClassId()))
                        .toList();
            }
            default -> List.of();
        };

        Map<String, Course> courses = courseRepository.findAll().stream()
                .collect(Collectors.toMap(Course::getCourseId, Function.identity()));

        return classes.stream().map(item -> {
            Course course = courses.get(item.getCourseId());
            List<String> teacherNames = teacherNames(item.getClassId());
            return new ClassSummaryResponse(
                    item.getClassId(),
                    item.getCourseId(),
                    course == null ? item.getCourseId() : course.getName(),
                    item.getClassCode(),
                    item.getName(),
                    item.getStartDate(),
                    item.getEndDate(),
                    item.getStatus(),
                    item.getCreatedByUsername(),
                    item.getCreatedAt(),
                    classStudentRepository.countByClassIdAndStatusIgnoreCase(item.getClassId(), "ACTIVE"),
                    teacherNames
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public ClassDetailResponse getClassDetail(String classId, Authentication authentication) {
        AcademicClass classItem = requireClass(classId);
        requireViewAccess(classItem, actor(authentication));

        Course course = courseRepository.findById(classItem.getCourseId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Course of class does not exist"));

        List<ClassStudent> memberships =
                classStudentRepository.findByClassIdAndStatusIgnoreCase(classId, "ACTIVE");
        Map<String, Student> students = studentRepository.findAllById(
                memberships.stream().map(ClassStudent::getStudentId).toList()
        ).stream().collect(Collectors.toMap(Student::getStudentId, Function.identity()));

        List<StudentSummary> roster = memberships.stream()
                .map(membership -> {
                    Student student = students.get(membership.getStudentId());
                    if (student == null) return null;
                    return new StudentSummary(
                            student.getStudentId(),
                            student.getStudentCode(),
                            student.getFullName(),
                            student.getPhone(),
                            student.getEmail(),
                            student.getStatus(),
                            membership.getStatus(),
                            membership.getJoinedAt()
                    );
                })
                .filter(Objects::nonNull)
                .toList();

        List<TeachingSchedule> teaching =
                teachingScheduleRepository.findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(classId, "ASSIGNED");
        Map<String, Employee> teachingEmployees = employeesByIds(
                teaching.stream().map(TeachingSchedule::getTeacherId).toList()
        );
        List<TeachingScheduleResponse> teachingResponses = teaching.stream()
                .map(item -> new TeachingScheduleResponse(
                        item.getTeachingScheduleId(),
                        item.getTeacherId(),
                        Optional.ofNullable(teachingEmployees.get(item.getTeacherId()))
                                .map(Employee::getFullName).orElse(item.getTeacherId()),
                        item.getDate(),
                        item.getStartTime(),
                        item.getEndTime(),
                        item.getStatus()
                ))
                .toList();

        List<StaffSchedule> staff =
                staffScheduleRepository.findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(classId, "ASSIGNED");
        Map<String, Employee> staffEmployees = employeesByIds(
                staff.stream().map(StaffSchedule::getEmployeeId).toList()
        );
        List<StaffScheduleResponse> staffResponses = staff.stream()
                .map(item -> new StaffScheduleResponse(
                        item.getStaffScheduleId(),
                        item.getEmployeeId(),
                        Optional.ofNullable(staffEmployees.get(item.getEmployeeId()))
                                .map(Employee::getFullName).orElse(item.getEmployeeId()),
                        item.getDate(),
                        item.getStartTime(),
                        item.getEndTime(),
                        item.getWorkType(),
                        item.getStatus(),
                        item.getAssignmentSource()
                ))
                .toList();

        return new ClassDetailResponse(
                classItem.getClassId(),
                new CourseSummary(course.getCourseId(), course.getName()),
                classItem.getClassCode(),
                classItem.getName(),
                classItem.getStartDate(),
                classItem.getEndDate(),
                classItem.getStatus(),
                classItem.getCreatedByUsername(),
                classItem.getCreatedAt(),
                targetRequirementRepository.findByClassIdOrderByTargetTypeAsc(classId).stream()
                        .map(item -> new TargetRequirementResponse(
                                item.getClassTargetRequirementId(),
                                item.getTargetType(),
                                item.getRequiredTarget()))
                        .toList(),
                roster,
                teachingResponses,
                staffResponses,
                assignmentRepository.findByClassIdOrderByCreatedAtDesc(classId).stream()
                        .map(item -> new AssignmentResponse(
                                item.getAssignmentId(), item.getTeacherId(), item.getTitle(),
                                item.getDescription(), item.getDeadline(), item.getStatus(), item.getCreatedAt()))
                        .toList(),
                examRepository.findByClassIdOrderByExamDateDesc(classId).stream()
                        .map(item -> new ExamResponse(
                                item.getExamId(), item.getTeacherId(), item.getTitle(), item.getDescription(),
                                item.getDuration(), item.getExamDate(), item.getStatus(), item.getCreatedAt()))
                        .toList(),
                studentResultRepository.findByClassIdOrderByEvaluatedAtDesc(classId).stream()
                        .map(item -> new StudentResultResponse(
                                item.getStudentResultId(), item.getStudentId(), item.getAssignmentId(),
                                item.getExamId(), item.getScore(), item.getFeedback(),
                                item.getEvaluatedById(), item.getEvaluatedAt()))
                        .toList(),
                auditLogRepository.findTop50ByEntityTypeIgnoreCaseAndEntityIdOrderByCreatedAtDesc("CLASS", classId)
                        .stream()
                        .map(item -> new AuditLogResponse(
                                item.getAuditLogId(), item.getUsername(), item.getAction(),
                                item.getDescription(), item.getCreatedAt()))
                        .toList()
        );
    }

    @Transactional
    public ClassDetailResponse createClass(ClassUpsertRequest request, Authentication authentication) {
        Actor actor = actor(authentication);
        if (!Set.of("ADMIN", "CS").contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin or CS can create classes");
        }
        validateClassRequest(request, null);

        AcademicClass classItem = AcademicClass.builder()
                .classId(newId("class"))
                .courseId(request.courseId())
                .classCode(request.classCode().trim())
                .name(request.name().trim())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .status("DRAFT")
                .createdByUsername(actor.username())
                .createdAt(Instant.now())
                .build();
        classRepository.save(classItem);
        replaceRequirements(classItem.getClassId(), request.targetRequirements());

        if ("CS".equals(actor.role())) {
            Employee employee = requireEmployee(actor.username());
            classAccessScopeRepository.save(ClassAccessScope.builder()
                    .classAccessScopeId(newId("class-scope"))
                    .employeeId(employee.getEmployeeId())
                    .classId(classItem.getClassId())
                    .status("ACTIVE")
                    .source("CREATED_BY_CS")
                    .createdAt(Instant.now())
                    .build());
        }

        audit(actor.username(), "CREATE_CLASS", classItem.getClassId(),
                "Created class " + classItem.getClassCode());
        return getClassDetail(classItem.getClassId(), authentication);
    }

    @Transactional
    public ClassDetailResponse updateClass(
            String classId,
            ClassUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        requireManageAccess(classItem, actor);
        if ("CLOSED".equalsIgnoreCase(classItem.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Closed classes are read-only");
        }
        validateClassRequest(request, classId);

        boolean hasHistory =
                classStudentRepository.existsByClassId(classId)
                || teachingScheduleRepository.existsByClassId(classId)
                || staffScheduleRepository.existsByClassId(classId)
                || assignmentRepository.existsByClassId(classId)
                || examRepository.existsByClassId(classId)
                || studentResultRepository.existsByClassId(classId);

        if (hasHistory && !classItem.getCourseId().equals(request.courseId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Course cannot be changed after the class has history");
        }

        if (Set.of("RUNNING", "COMPLETED", "CLOSED").contains(classItem.getStatus())) {
            Map<String, BigDecimal> current = targetRequirementRepository
                    .findByClassIdOrderByTargetTypeAsc(classId)
                    .stream().collect(Collectors.toMap(
                            ClassTargetRequirement::getTargetType,
                            ClassTargetRequirement::getRequiredTarget));
            Map<String, BigDecimal> proposed = request.targetRequirements().stream()
                    .collect(Collectors.toMap(
                            item -> item.targetType().trim().toUpperCase(),
                            TargetRequirementInput::requiredTarget,
                            (left, right) -> right));
            if (!current.equals(proposed)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Class target requirements are locked once the class is running");
            }
        } else {
            ensureRosterStillEligible(classItem, request.courseId(), request.targetRequirements());
            replaceRequirements(classId, request.targetRequirements());
        }

        classItem.setCourseId(request.courseId());
        classItem.setClassCode(request.classCode().trim());
        classItem.setName(request.name().trim());
        classItem.setStartDate(request.startDate());
        classItem.setEndDate(request.endDate());
        classRepository.save(classItem);

        audit(actor.username(), "UPDATE_CLASS", classId,
                "Updated class " + classItem.getClassCode());
        return getClassDetail(classId, authentication);
    }

    @Transactional
    public ClassDetailResponse changeStatus(
            String classId,
            ChangeStatusRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        requireManageAccess(classItem, actor);

        String nextStatus = request.status().trim().toUpperCase();
        int currentIndex = CLASS_STATUSES.indexOf(classItem.getStatus());
        if (currentIndex < 0 || currentIndex + 1 >= CLASS_STATUSES.size()
                || !CLASS_STATUSES.get(currentIndex + 1).equals(nextStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "Invalid class status transition");
        }

        if (Set.of("READY", "RUNNING").contains(nextStatus)) {
            validateTargetRequirements(classItem.getCourseId(),
                    targetRequirementRepository.findByClassIdOrderByTargetTypeAsc(classId).stream()
                            .map(item -> new TargetRequirementInput(item.getTargetType(), item.getRequiredTarget()))
                            .toList());
        }

        if ("RUNNING".equals(nextStatus)) {
            boolean hasValidSchedule = teachingScheduleRepository
                    .findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(classId, "ASSIGNED")
                    .stream()
                    .anyMatch(schedule ->
                            !schedule.getStartTime().isAfter(schedule.getEndTime())
                            && schedule.getStartTime().isBefore(schedule.getEndTime())
                            && !schedule.getDate().isBefore(classItem.getStartDate())
                            && !schedule.getDate().isAfter(classItem.getEndDate()));
            if (!hasValidSchedule) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "A valid assigned teaching schedule is required before the class can run");
            }
        }

        if ("COMPLETED".equals(nextStatus)
                && (assignmentRepository.existsByClassIdAndStatusIgnoreCase(classId, "OPEN")
                || examRepository.existsByClassIdAndStatusIgnoreCase(classId, "SCHEDULED"))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Close/cancel open assignments and complete/cancel scheduled exams first");
        }

        String previous = classItem.getStatus();
        classItem.setStatus(nextStatus);
        classRepository.save(classItem);
        audit(actor.username(), "CHANGE_CLASS_STATUS", classId,
                "Changed class status from " + previous + " to " + nextStatus);
        return getClassDetail(classId, authentication);
    }

    @Transactional
    public RosterChangeResponse addStudents(
            String classId,
            AddStudentsRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        requireManageAccess(classItem, actor);
        requireAssignableClass(classItem);

        List<String> ids = request.studentIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "studentIds cannot be empty");
        }

        List<String> affected = new ArrayList<>();
        for (String studentId : ids) {
            Student student = studentRepository.findById(studentId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "Student not found: " + studentId));
            EligibilityResponse eligibility = eligibility(student, classItem);
            if (!eligibility.eligible()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        student.getFullName() + " is not eligible: " + String.join("; ", eligibility.reasons()));
            }

            Optional<ClassStudent> existing = classStudentRepository.findByClassIdAndStudentId(classId, studentId);
            if (existing.isPresent() && "ACTIVE".equalsIgnoreCase(existing.get().getStatus())) {
                continue;
            }

            ClassStudent membership = existing.orElseGet(() -> ClassStudent.builder()
                    .classStudentId(newId("class-student"))
                    .classId(classId)
                    .studentId(studentId)
                    .joinedAt(Instant.now())
                    .build());
            membership.setStatus("ACTIVE");
            membership.setInactiveReason(null);
            membership.setInactivatedAt(null);
            classStudentRepository.save(membership);
            affected.add(studentId);
        }

        if (affected.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Selected students are already active in this class");
        }

        audit(actor.username(), "ADD_STUDENTS_TO_CLASS", classId,
                "Added students: " + String.join(", ", affected));
        return new RosterChangeResponse(affected.size(), affected);
    }

    @Transactional
    public RosterChangeResponse removeStudent(
            String classId,
            String studentId,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        requireManageAccess(classItem, actor);
        requireAssignableClass(classItem);

        ClassStudent membership = classStudentRepository.findByClassIdAndStudentId(classId, studentId)
                .filter(item -> "ACTIVE".equalsIgnoreCase(item.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Student is not currently active in this class"));

        membership.setStatus("INACTIVE");
        membership.setInactiveReason("REMOVED_FROM_CLASS");
        membership.setInactivatedAt(Instant.now());
        classStudentRepository.save(membership);

        audit(actor.username(), "REMOVE_STUDENT_FROM_CLASS", classId,
                "Removed student " + studentId + " from active roster");
        return new RosterChangeResponse(1, List.of(studentId));
    }

    @Transactional(readOnly = true)
    public List<StudentCandidateResponse> listStudentCandidates(
            String classId,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        requireManageAccess(classItem, actor(authentication));

        Set<String> activeStudentIds = classStudentRepository
                .findByClassIdAndStatusIgnoreCase(classId, "ACTIVE")
                .stream()
                .map(ClassStudent::getStudentId)
                .collect(Collectors.toSet());

        return studentRepository.findAllByOrderByFullNameAsc().stream()
                .map(student -> new StudentCandidateResponse(
                        new StudentSummary(
                                student.getStudentId(),
                                student.getStudentCode(),
                                student.getFullName(),
                                student.getPhone(),
                                student.getEmail(),
                                student.getStatus(),
                                activeStudentIds.contains(student.getStudentId()) ? "ACTIVE" : null,
                                null
                        ),
                        activeStudentIds.contains(student.getStudentId()),
                        eligibility(student, classItem)
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public EligibilityResponse getStudentEligibility(
            String classId,
            String studentId,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        requireViewAccess(classItem, actor(authentication));
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Student not found"));
        return eligibility(student, classItem);
    }

    @Transactional
    public AssignmentResponse createAssignment(
            String classId,
            AssignmentUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);
        validateDateInsideClass(request.deadline(), classItem, "Assignment deadline");

        Assignment assignment = Assignment.builder()
                .assignmentId(newId("assignment"))
                .teacherId(teacher.getEmployeeId())
                .classId(classId)
                .title(request.title().trim())
                .description(trimToNull(request.description()))
                .deadline(request.deadline().atStartOfDay().toInstant(ZoneOffset.UTC))
                .status("OPEN")
                .createdAt(Instant.now())
                .build();
        assignmentRepository.save(assignment);
        audit(actor.username(), "CREATE_ASSIGNMENT", classId,
                "Created assignment " + assignment.getAssignmentId());
        return toAssignmentResponse(assignment);
    }

    @Transactional
    public AssignmentResponse updateAssignment(
            String classId,
            String assignmentId,
            AssignmentUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .filter(item -> item.getClassId().equals(classId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignment not found"));
        if (!assignment.getTeacherId().equals(teacher.getEmployeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Teacher can only edit their own assignment");
        }
        if (!"OPEN".equalsIgnoreCase(assignment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only open assignments can be edited");
        }
        if (studentResultRepository.existsByAssignmentId(assignmentId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Assignment details are locked after results are recorded");
        }
        validateDateInsideClass(request.deadline(), classItem, "Assignment deadline");

        assignment.setTitle(request.title().trim());
        assignment.setDescription(trimToNull(request.description()));
        assignment.setDeadline(request.deadline().atStartOfDay().toInstant(ZoneOffset.UTC));
        assignmentRepository.save(assignment);
        audit(actor.username(), "UPDATE_ASSIGNMENT", classId,
                "Updated assignment " + assignmentId);
        return toAssignmentResponse(assignment);
    }

    @Transactional
    public AssignmentResponse changeAssignmentStatus(
            String classId,
            String assignmentId,
            AssignmentStatusRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);

        Assignment assignment = assignmentRepository.findById(assignmentId)
                .filter(item -> item.getClassId().equals(classId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Assignment not found"));
        if (!assignment.getTeacherId().equals(teacher.getEmployeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Teacher can only manage their own assignment");
        }

        String next = request.status().trim().toUpperCase();
        if (!"OPEN".equalsIgnoreCase(assignment.getStatus())
                || !Set.of("CLOSED", "CANCELLED").contains(next)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Invalid assignment status transition");
        }
        if ("CANCELLED".equals(next)
                && studentResultRepository.existsByAssignmentId(assignmentId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Assignment with recorded results cannot be cancelled");
        }

        assignment.setStatus(next);
        assignmentRepository.save(assignment);
        audit(actor.username(), "CHANGE_ASSIGNMENT_STATUS", classId,
                "Changed assignment " + assignmentId + " to " + next);
        return toAssignmentResponse(assignment);
    }

    @Transactional
    public ExamResponse createExam(
            String classId,
            ExamUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);
        if (request.duration() == null || request.duration() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Exam duration must be positive");
        }
        validateDateInsideClass(request.examDate(), classItem, "Exam date");

        Exam exam = Exam.builder()
                .examId(newId("exam"))
                .teacherId(teacher.getEmployeeId())
                .classId(classId)
                .title(request.title().trim())
                .description(trimToNull(request.description()))
                .duration(request.duration())
                .examDate(request.examDate().atStartOfDay().toInstant(ZoneOffset.UTC))
                .status("SCHEDULED")
                .createdAt(Instant.now())
                .build();
        examRepository.save(exam);
        audit(actor.username(), "CREATE_EXAM", classId,
                "Created exam " + exam.getExamId());
        return toExamResponse(exam);
    }

    @Transactional
    public ExamResponse updateExam(
            String classId,
            String examId,
            ExamUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);

        Exam exam = examRepository.findById(examId)
                .filter(item -> item.getClassId().equals(classId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));
        if (!exam.getTeacherId().equals(teacher.getEmployeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Teacher can only edit their own exam");
        }
        if (!"SCHEDULED".equalsIgnoreCase(exam.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only scheduled exams can be edited");
        }
        if (studentResultRepository.existsByExamId(examId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Exam details are locked after results are recorded");
        }
        if (request.duration() == null || request.duration() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Exam duration must be positive");
        }
        validateDateInsideClass(request.examDate(), classItem, "Exam date");

        exam.setTitle(request.title().trim());
        exam.setDescription(trimToNull(request.description()));
        exam.setDuration(request.duration());
        exam.setExamDate(request.examDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        examRepository.save(exam);
        audit(actor.username(), "UPDATE_EXAM", classId,
                "Updated exam " + examId);
        return toExamResponse(exam);
    }

    @Transactional
    public ExamResponse changeExamStatus(
            String classId,
            String examId,
            ExamStatusRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);
        requireTeachingActivityClass(classItem);

        Exam exam = examRepository.findById(examId)
                .filter(item -> item.getClassId().equals(classId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));
        if (!exam.getTeacherId().equals(teacher.getEmployeeId())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Teacher can only manage their own exam");
        }

        String next = request.status().trim().toUpperCase();
        if (!"SCHEDULED".equalsIgnoreCase(exam.getStatus())
                || !Set.of("COMPLETED", "CANCELLED").contains(next)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Invalid exam status transition");
        }
        if ("CANCELLED".equals(next)
                && studentResultRepository.existsByExamId(examId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Exam with recorded results cannot be cancelled");
        }

        exam.setStatus(next);
        examRepository.save(exam);
        audit(actor.username(), "CHANGE_EXAM_STATUS", classId,
                "Changed exam " + examId + " to " + next);
        return toExamResponse(exam);
    }

    @Transactional
    public StudentResultResponse upsertStudentResult(
            String classId,
            StudentResultUpsertRequest request,
            Authentication authentication
    ) {
        AcademicClass classItem = requireClass(classId);
        Actor actor = actor(authentication);
        Employee teacher = requireAssignedTeacher(actor, classId);

        if (!Set.of("READY", "RUNNING", "COMPLETED").contains(classItem.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Results can only be recorded for Ready, Running or Completed classes");
        }

        boolean hasAssignment = request.assignmentId() != null && !request.assignmentId().isBlank();
        boolean hasExam = request.examId() != null && !request.examId().isBlank();
        if (hasAssignment == hasExam) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Result must reference exactly one assignment or exam");
        }

        ClassStudent membership = classStudentRepository
                .findByClassIdAndStudentId(classId, request.studentId())
                .filter(item -> "ACTIVE".equalsIgnoreCase(item.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "Student is not currently active in this class"));

        if (membership == null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Student is not currently active in this class");
        }

        StudentResult result;
        if (hasAssignment) {
            Assignment assignment = assignmentRepository.findById(request.assignmentId())
                    .filter(item -> item.getClassId().equals(classId))
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "Assignment does not belong to this class"));
            if ("CANCELLED".equalsIgnoreCase(assignment.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Results cannot be recorded for a cancelled assignment");
            }
            result = studentResultRepository
                    .findByStudentIdAndClassIdAndAssignmentId(
                            request.studentId(), classId, request.assignmentId())
                    .orElseGet(() -> StudentResult.builder()
                            .studentResultId(newId("result"))
                            .studentId(request.studentId())
                            .classId(classId)
                            .assignmentId(request.assignmentId())
                            .examId(null)
                            .build());
        } else {
            Exam exam = examRepository.findById(request.examId())
                    .filter(item -> item.getClassId().equals(classId))
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                            "Exam does not belong to this class"));
            if (!"COMPLETED".equalsIgnoreCase(exam.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Exam must be completed before results are recorded");
            }
            result = studentResultRepository
                    .findByStudentIdAndClassIdAndExamId(
                            request.studentId(), classId, request.examId())
                    .orElseGet(() -> StudentResult.builder()
                            .studentResultId(newId("result"))
                            .studentId(request.studentId())
                            .classId(classId)
                            .assignmentId(null)
                            .examId(request.examId())
                            .build());
        }

        result.setScore(request.score());
        result.setFeedback(trimToNull(request.feedback()));
        result.setEvaluatedById(teacher.getEmployeeId());
        result.setEvaluatedAt(Instant.now());
        studentResultRepository.save(result);

        audit(actor.username(), "EVALUATE_STUDENT", classId,
                "Recorded result for student " + request.studentId());
        return toStudentResultResponse(result);
    }

    @Transactional
    public StaffScheduleResponse overrideSupport(
            String classId,
            String scheduleId,
            SupportOverrideRequest request,
            Authentication authentication
    ) {
        Actor actor = actor(authentication);
        if (!"ADMIN".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only Admin can perform support overrides");
        }

        AcademicClass classItem = requireClass(classId);
        if ("CLOSED".equalsIgnoreCase(classItem.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Closed classes are read-only");
        }

        StaffSchedule schedule = staffScheduleRepository.findById(scheduleId)
                .filter(item -> classId.equals(item.getClassId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Support schedule not found"));

        Employee newCs = employeeRepository.findById(request.newCsId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Replacement CS not found"));
        User user = userRepository.findByUsername(newCs.getUsername())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "Replacement employee has no user account"));
        if (!"CS".equalsIgnoreCase(user.getRoleId())
                || !"ACTIVE".equalsIgnoreCase(newCs.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Replacement employee must be an active CS");
        }

        List<StaffSchedule> conflicts = staffScheduleRepository
                .findByEmployeeIdAndDateAndStatusIgnoreCase(
                        newCs.getEmployeeId(), schedule.getDate(), "ASSIGNED")
                .stream()
                .filter(item -> !item.getStaffScheduleId().equals(scheduleId))
                .filter(item -> overlaps(
                        schedule.getStartTime(), schedule.getEndTime(),
                        item.getStartTime(), item.getEndTime()))
                .toList();

        if (!conflicts.isEmpty() && !request.allowConflict()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Replacement CS has an overlapping assigned shift");
        }

        schedule.setEmployeeId(newCs.getEmployeeId());
        // Admin accounts are not required to have an Employee row in this schema.
        // Keep the existing employee-based assigned_by value and rely on AuditLog
        // to record the authenticated admin who performed the override.
        schedule.setAssignmentSource("ADMIN_OVERRIDE");
        staffScheduleRepository.save(schedule);

        audit(actor.username(), "OVERRIDE_CS_SUPPORT", classId,
                "Reassigned support schedule " + scheduleId + " to "
                        + newCs.getEmployeeId() + ". Reason: " + request.reason().trim());
        return new StaffScheduleResponse(
                schedule.getStaffScheduleId(),
                schedule.getEmployeeId(),
                newCs.getFullName(),
                schedule.getDate(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getWorkType(),
                schedule.getStatus(),
                schedule.getAssignmentSource());
    }

    @Transactional(readOnly = true)
    public List<CourseSummary> listCourses() {
        return courseRepository.findByStatusIgnoreCaseOrderByNameAsc("ACTIVE").stream()
                .map(item -> new CourseSummary(item.getCourseId(), item.getName()))
                .toList();
    }

    private EligibilityResponse eligibility(Student student, AcademicClass classItem) {
        List<String> reasons = new ArrayList<>();
        if (!"Active".equalsIgnoreCase(student.getStatus())) {
            reasons.add("Student status must be Active");
        }
        if (!ASSIGNABLE_STATUSES.contains(classItem.getStatus())) {
            reasons.add("Class is not accepting roster changes");
        }

        Map<String, BigDecimal> required = targetRequirementRepository
                .findByClassIdOrderByTargetTypeAsc(classItem.getClassId())
                .stream().collect(Collectors.toMap(
                        ClassTargetRequirement::getTargetType,
                        ClassTargetRequirement::getRequiredTarget));
        Map<String, BigDecimal> targets = studentTargetRepository
                .findByStudentIdAndCourseId(student.getStudentId(), classItem.getCourseId())
                .stream().collect(Collectors.toMap(
                        StudentTarget::getTargetType,
                        StudentTarget::getTargetValue,
                        (left, right) -> right));

        for (Map.Entry<String, BigDecimal> requirement : required.entrySet()) {
            BigDecimal value = targets.get(requirement.getKey());
            if (value == null) {
                reasons.add("Missing target " + requirement.getKey());
            } else if (value.compareTo(requirement.getValue()) < 0) {
                reasons.add(requirement.getKey() + " target is below class requirement");
            }
        }

        return new EligibilityResponse(
                reasons.isEmpty(),
                reasons.isEmpty() ? null : "TARGET_OR_STATUS_MISMATCH",
                reasons,
                required,
                targets
        );
    }

    private Employee requireAssignedTeacher(Actor actor, String classId) {
        if (!"TEACHER".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Only an assigned teacher can manage teaching activities");
        }
        Employee teacher = requireEmployee(actor.username());
        if (!teachingScheduleRepository.existsByTeacherIdAndClassIdAndStatusIgnoreCase(
                teacher.getEmployeeId(), classId, "ASSIGNED")) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Teacher is not assigned to this class");
        }
        return teacher;
    }

    private void requireTeachingActivityClass(AcademicClass classItem) {
        if (!Set.of("READY", "RUNNING").contains(classItem.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Teaching activities can only be managed while class is Ready or Running");
        }
    }

    private void validateDateInsideClass(
            LocalDate date,
            AcademicClass classItem,
            String label
    ) {
        if (date.isBefore(classItem.getStartDate()) || date.isAfter(classItem.getEndDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    label + " must be within the class date range");
        }
    }

    private boolean overlaps(
            java.time.LocalTime aStart,
            java.time.LocalTime aEnd,
            java.time.LocalTime bStart,
            java.time.LocalTime bEnd
    ) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private AssignmentResponse toAssignmentResponse(Assignment item) {
        return new AssignmentResponse(
                item.getAssignmentId(),
                item.getTeacherId(),
                item.getTitle(),
                item.getDescription(),
                item.getDeadline(),
                item.getStatus(),
                item.getCreatedAt());
    }

    private ExamResponse toExamResponse(Exam item) {
        return new ExamResponse(
                item.getExamId(),
                item.getTeacherId(),
                item.getTitle(),
                item.getDescription(),
                item.getDuration(),
                item.getExamDate(),
                item.getStatus(),
                item.getCreatedAt());
    }

    private StudentResultResponse toStudentResultResponse(StudentResult item) {
        return new StudentResultResponse(
                item.getStudentResultId(),
                item.getStudentId(),
                item.getAssignmentId(),
                item.getExamId(),
                item.getScore(),
                item.getFeedback(),
                item.getEvaluatedById(),
                item.getEvaluatedAt());
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateClassRequest(ClassUpsertRequest request, String currentClassId) {
        if (!request.startDate().isBefore(request.endDate()) && !request.startDate().isEqual(request.endDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Class end date must be on or after start date");
        }
        if (!courseRepository.existsById(request.courseId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Course does not exist");
        }
        String code = request.classCode().trim();
        boolean duplicate = currentClassId == null
                ? classRepository.existsByClassCodeIgnoreCase(code)
                : classRepository.existsByClassCodeIgnoreCaseAndClassIdNot(code, currentClassId);
        if (duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "Class code already exists");
        }
        validateTargetRequirements(request.courseId(), request.targetRequirements());
    }

    private void validateTargetRequirements(
            String courseId,
            List<TargetRequirementInput> requirements
    ) {
        Map<String, BigDecimal> values = requirements.stream().collect(Collectors.toMap(
                item -> item.targetType().trim().toUpperCase(),
                TargetRequirementInput::requiredTarget,
                (left, right) -> right
        ));

        switch (courseId) {
            case "course-toeic" -> {
                validateTarget(values, "LR_TOTAL", new BigDecimal("10"), new BigDecimal("990"), new BigDecimal("5"));
                validateTarget(values, "SW_TOTAL", BigDecimal.ZERO, new BigDecimal("400"), new BigDecimal("10"));
                requireExactTargets(values, Set.of("LR_TOTAL", "SW_TOTAL"));
            }
            case "course-ielts" -> {
                validateTarget(values, "OVERALL_BAND", BigDecimal.ZERO, new BigDecimal("9"), new BigDecimal("0.5"));
                requireExactTargets(values, Set.of("OVERALL_BAND"));
            }
            case "course-sat" -> {
                validateTarget(values, "TOTAL", new BigDecimal("400"), new BigDecimal("1600"), new BigDecimal("10"));
                requireExactTargets(values, Set.of("TOTAL"));
            }
            case "course-toefl" -> {
                validateTarget(values, "OVERALL_1_6", BigDecimal.ONE, new BigDecimal("6"), new BigDecimal("0.5"));
                requireExactTargets(values, Set.of("OVERALL_1_6"));
            }
            default -> {
                if (values.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "Class target requirements are required");
                }
            }
        }
    }

    private void validateTarget(
            Map<String, BigDecimal> values,
            String key,
            BigDecimal min,
            BigDecimal max,
            BigDecimal step
    ) {
        BigDecimal value = values.get(key);
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing required target " + key);
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    key + " must be between " + min + " and " + max);
        }
        BigDecimal offset = value.subtract(min);
        if (offset.remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    key + " must use step " + step);
        }
    }

    private void requireExactTargets(Map<String, BigDecimal> values, Set<String> expected) {
        if (!values.keySet().equals(expected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Target types must be exactly " + expected);
        }
    }

    private void replaceRequirements(String classId, List<TargetRequirementInput> requirements) {
        // Flush removals before inserting the replacement rows.
        // class_target_requirements has a unique constraint on (class_id, target_type),
        // so queuing delete + insert in the same persistence context can otherwise
        // attempt the insert first and trigger a duplicate-key error.
        List<ClassTargetRequirement> existing =
                targetRequirementRepository.findByClassIdOrderByTargetTypeAsc(classId);
        if (!existing.isEmpty()) {
            targetRequirementRepository.deleteAll(existing);
            targetRequirementRepository.flush();
        }

        List<ClassTargetRequirement> entities = requirements.stream()
                .map(item -> ClassTargetRequirement.builder()
                        .classTargetRequirementId(newId("class-target"))
                        .classId(classId)
                        .targetType(item.targetType().trim().toUpperCase())
                        .requiredTarget(item.requiredTarget())
                        .createdAt(Instant.now())
                        .build())
                .toList();
        targetRequirementRepository.saveAll(entities);
    }

    private void ensureRosterStillEligible(
            AcademicClass currentClass,
            String proposedCourseId,
            List<TargetRequirementInput> proposedRequirements
    ) {
        List<ClassStudent> memberships =
                classStudentRepository.findByClassIdAndStatusIgnoreCase(currentClass.getClassId(), "ACTIVE");
        if (memberships.isEmpty()) return;

        Map<String, BigDecimal> required = proposedRequirements.stream()
                .collect(Collectors.toMap(
                        item -> item.targetType().trim().toUpperCase(),
                        TargetRequirementInput::requiredTarget,
                        (left, right) -> right));

        for (ClassStudent membership : memberships) {
            Student student = studentRepository.findById(membership.getStudentId()).orElse(null);
            if (student == null || !"Active".equalsIgnoreCase(student.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "New class configuration would make an active roster member ineligible");
            }

            Map<String, BigDecimal> targets = studentTargetRepository
                    .findByStudentIdAndCourseId(student.getStudentId(), proposedCourseId)
                    .stream().collect(Collectors.toMap(
                            StudentTarget::getTargetType,
                            StudentTarget::getTargetValue,
                            (left, right) -> right));

            for (Map.Entry<String, BigDecimal> requirement : required.entrySet()) {
                BigDecimal actual = targets.get(requirement.getKey());
                if (actual == null || actual.compareTo(requirement.getValue()) < 0) {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "New class target would make " + student.getFullName() + " ineligible");
                }
            }
        }
    }

    private void requireAssignableClass(AcademicClass classItem) {
        if (!ASSIGNABLE_STATUSES.contains(classItem.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Cannot change roster of a " + classItem.getStatus().toLowerCase() + " class");
        }
    }

    private AcademicClass requireClass(String classId) {
        return classRepository.findById(classId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Class not found"));
    }

    private Employee requireEmployee(String username) {
        return employeeRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "Authenticated user is not linked to an employee"));
    }

    private void requireViewAccess(AcademicClass classItem, Actor actor) {
        if ("ADMIN".equals(actor.role())) return;

        Employee employee = requireEmployee(actor.username());
        if ("CS".equals(actor.role())
                && classAccessScopeRepository.existsByEmployeeIdAndClassIdAndStatusIgnoreCase(
                employee.getEmployeeId(), classItem.getClassId(), "ACTIVE")) {
            return;
        }
        if ("TEACHER".equals(actor.role())
                && teachingScheduleRepository.existsByTeacherIdAndClassIdAndStatusIgnoreCase(
                employee.getEmployeeId(), classItem.getClassId(), "ASSIGNED")) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You do not have access to this class");
    }

    private void requireManageAccess(AcademicClass classItem, Actor actor) {
        if ("ADMIN".equals(actor.role())) return;
        if (!"CS".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Admin or assigned CS can manage a class");
        }
        Employee employee = requireEmployee(actor.username());
        if (!classAccessScopeRepository.existsByEmployeeIdAndClassIdAndStatusIgnoreCase(
                employee.getEmployeeId(), classItem.getClassId(), "ACTIVE")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not manage this class");
        }
    }

    private List<String> teacherNames(String classId) {
        List<TeachingSchedule> schedules =
                teachingScheduleRepository.findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(
                        classId, "ASSIGNED");
        Map<String, Employee> employees = employeesByIds(
                schedules.stream().map(TeachingSchedule::getTeacherId).toList());
        return schedules.stream()
                .map(TeachingSchedule::getTeacherId)
                .distinct()
                .map(id -> Optional.ofNullable(employees.get(id))
                        .map(Employee::getFullName).orElse(id))
                .toList();
    }

    private Map<String, Employee> employeesByIds(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity()));
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

    private void audit(String username, String action, String classId, String description) {
        auditLogRepository.save(AuditLog.builder()
                .auditLogId(newId("audit"))
                .username(username)
                .action(action)
                .entityType("CLASS")
                .entityId(classId)
                .description(description)
                .createdAt(Instant.now())
                .build());
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private record Actor(String username, String role) {}
}

package com.example.demo.controller;

import com.example.demo.dto.ClassDtos.*;
import com.example.demo.service.ClassManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/classes")
@RequiredArgsConstructor
public class ClassController {

    private final ClassManagementService classManagementService;

    @GetMapping
    public List<ClassSummaryResponse> list(Authentication authentication) {
        return classManagementService.listClasses(authentication);
    }

    @GetMapping("/{classId}")
    public ClassDetailResponse detail(
            @PathVariable String classId,
            Authentication authentication
    ) {
        return classManagementService.getClassDetail(classId, authentication);
    }

    @PostMapping
    public ResponseEntity<ClassDetailResponse> create(
            @Valid @RequestBody ClassUpsertRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(classManagementService.createClass(request, authentication));
    }

    @PutMapping("/{classId}")
    public ClassDetailResponse update(
            @PathVariable String classId,
            @Valid @RequestBody ClassUpsertRequest request,
            Authentication authentication
    ) {
        return classManagementService.updateClass(classId, request, authentication);
    }

    @PatchMapping("/{classId}/status")
    public ClassDetailResponse changeStatus(
            @PathVariable String classId,
            @Valid @RequestBody ChangeStatusRequest request,
            Authentication authentication
    ) {
        return classManagementService.changeStatus(classId, request, authentication);
    }

    @PostMapping("/{classId}/students")
    public RosterChangeResponse addStudents(
            @PathVariable String classId,
            @Valid @RequestBody AddStudentsRequest request,
            Authentication authentication
    ) {
        return classManagementService.addStudents(classId, request, authentication);
    }

    @DeleteMapping("/{classId}/students/{studentId}")
    public RosterChangeResponse removeStudent(
            @PathVariable String classId,
            @PathVariable String studentId,
            Authentication authentication
    ) {
        return classManagementService.removeStudent(classId, studentId, authentication);
    }

    @GetMapping("/{classId}/student-candidates")
    public List<StudentCandidateResponse> studentCandidates(
            @PathVariable String classId,
            Authentication authentication
    ) {
        return classManagementService.listStudentCandidates(classId, authentication);
    }

    @GetMapping("/{classId}/students/{studentId}/eligibility")
    public EligibilityResponse eligibility(
            @PathVariable String classId,
            @PathVariable String studentId,
            Authentication authentication
    ) {
        return classManagementService.getStudentEligibility(classId, studentId, authentication);
    }

    @PostMapping("/{classId}/assignments")
    public ResponseEntity<AssignmentResponse> createAssignment(
            @PathVariable String classId,
            @Valid @RequestBody AssignmentUpsertRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(classManagementService.createAssignment(classId, request, authentication));
    }

    @PutMapping("/{classId}/assignments/{assignmentId}")
    public AssignmentResponse updateAssignment(
            @PathVariable String classId,
            @PathVariable String assignmentId,
            @Valid @RequestBody AssignmentUpsertRequest request,
            Authentication authentication
    ) {
        return classManagementService.updateAssignment(classId, assignmentId, request, authentication);
    }

    @PatchMapping("/{classId}/assignments/{assignmentId}/status")
    public AssignmentResponse changeAssignmentStatus(
            @PathVariable String classId,
            @PathVariable String assignmentId,
            @Valid @RequestBody AssignmentStatusRequest request,
            Authentication authentication
    ) {
        return classManagementService.changeAssignmentStatus(classId, assignmentId, request, authentication);
    }

    @PostMapping("/{classId}/exams")
    public ResponseEntity<ExamResponse> createExam(
            @PathVariable String classId,
            @Valid @RequestBody ExamUpsertRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(classManagementService.createExam(classId, request, authentication));
    }

    @PutMapping("/{classId}/exams/{examId}")
    public ExamResponse updateExam(
            @PathVariable String classId,
            @PathVariable String examId,
            @Valid @RequestBody ExamUpsertRequest request,
            Authentication authentication
    ) {
        return classManagementService.updateExam(classId, examId, request, authentication);
    }

    @PatchMapping("/{classId}/exams/{examId}/status")
    public ExamResponse changeExamStatus(
            @PathVariable String classId,
            @PathVariable String examId,
            @Valid @RequestBody ExamStatusRequest request,
            Authentication authentication
    ) {
        return classManagementService.changeExamStatus(classId, examId, request, authentication);
    }

    @PutMapping("/{classId}/results")
    public StudentResultResponse upsertStudentResult(
            @PathVariable String classId,
            @Valid @RequestBody StudentResultUpsertRequest request,
            Authentication authentication
    ) {
        return classManagementService.upsertStudentResult(classId, request, authentication);
    }

    @PatchMapping("/{classId}/staff-schedules/{scheduleId}/override")
    public StaffScheduleResponse overrideSupport(
            @PathVariable String classId,
            @PathVariable String scheduleId,
            @Valid @RequestBody SupportOverrideRequest request,
            Authentication authentication
    ) {
        return classManagementService.overrideSupport(classId, scheduleId, request, authentication);
    }

    @GetMapping("/courses")
    public List<CourseSummary> courses() {
        return classManagementService.listCourses();
    }
}

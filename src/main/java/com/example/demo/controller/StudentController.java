package com.example.demo.controller;

import com.example.demo.dto.StudentDtos.*;
import com.example.demo.service.StudentManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentManagementService studentManagementService;

    @GetMapping
    public List<StudentSummaryResponse> list(Authentication authentication) {
        return studentManagementService.listStudents(authentication);
    }

    @GetMapping("/{studentId}")
    public StudentDetailResponse detail(
            @PathVariable String studentId,
            Authentication authentication
    ) {
        return studentManagementService.getStudent(studentId, authentication);
    }

    @PostMapping
    public ResponseEntity<StudentDetailResponse> create(
            @Valid @RequestBody StudentUpsertRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(studentManagementService.createStudent(request, authentication));
    }

    @PutMapping("/{studentId}")
    public StudentDetailResponse update(
            @PathVariable String studentId,
            @Valid @RequestBody StudentUpsertRequest request,
            Authentication authentication
    ) {
        return studentManagementService.updateStudent(studentId, request, authentication);
    }

    @PatchMapping("/{studentId}/status")
    public StatusChangeResponse changeStatus(
            @PathVariable String studentId,
            @Valid @RequestBody ChangeStudentStatusRequest request,
            Authentication authentication
    ) {
        return studentManagementService.changeStatus(studentId, request, authentication);
    }

    @DeleteMapping("/{studentId}")
    public ResponseEntity<Void> delete(
            @PathVariable String studentId,
            Authentication authentication
    ) {
        studentManagementService.deleteStudent(studentId, authentication);
        return ResponseEntity.noContent().build();
    }
}

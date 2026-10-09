package com.example.demo.repository;

import com.example.demo.entity.StudentResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentResultRepository extends JpaRepository<StudentResult, String> {
    List<StudentResult> findByClassIdOrderByEvaluatedAtDesc(String classId);
    List<StudentResult> findByStudentIdOrderByEvaluatedAtDesc(String studentId);
    boolean existsByClassId(String classId);
    boolean existsByStudentId(String studentId);
    boolean existsByAssignmentId(String assignmentId);
    boolean existsByExamId(String examId);
    Optional<StudentResult> findByStudentIdAndClassIdAndAssignmentId(
            String studentId, String classId, String assignmentId);
    Optional<StudentResult> findByStudentIdAndClassIdAndExamId(
            String studentId, String classId, String examId);
}

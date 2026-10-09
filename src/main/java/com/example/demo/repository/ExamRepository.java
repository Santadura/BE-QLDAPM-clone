package com.example.demo.repository;

import com.example.demo.entity.Exam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamRepository extends JpaRepository<Exam, String> {
    List<Exam> findByClassIdOrderByExamDateDesc(String classId);
    boolean existsByClassId(String classId);
    boolean existsByClassIdAndStatusIgnoreCase(String classId, String status);
    boolean existsByExamIdAndClassId(String examId, String classId);
}

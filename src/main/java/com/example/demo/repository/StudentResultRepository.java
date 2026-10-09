package com.example.demo.repository;

import com.example.demo.entity.StudentResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentResultRepository extends JpaRepository<StudentResult, String> {
    List<StudentResult> findByClassIdOrderByEvaluatedAtDesc(String classId);
    boolean existsByClassId(String classId);
}

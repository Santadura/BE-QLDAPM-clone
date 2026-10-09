package com.example.demo.repository;

import com.example.demo.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentRepository extends JpaRepository<Assignment, String> {
    List<Assignment> findByClassIdOrderByCreatedAtDesc(String classId);
    boolean existsByClassId(String classId);
    boolean existsByClassIdAndStatusIgnoreCase(String classId, String status);
}

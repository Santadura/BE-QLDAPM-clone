package com.example.demo.repository;

import com.example.demo.entity.AcademicClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcademicClassRepository extends JpaRepository<AcademicClass, String> {
    List<AcademicClass> findAllByOrderByCreatedAtDesc();
    boolean existsByClassCodeIgnoreCase(String classCode);
    boolean existsByClassCodeIgnoreCaseAndClassIdNot(String classCode, String classId);
}

package com.example.demo.repository;

import com.example.demo.entity.ClassStudent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassStudentRepository extends JpaRepository<ClassStudent, String> {
    List<ClassStudent> findByClassIdAndStatusIgnoreCase(String classId, String status);
    Optional<ClassStudent> findByClassIdAndStudentId(String classId, String studentId);
    boolean existsByClassId(String classId);
    long countByClassIdAndStatusIgnoreCase(String classId, String status);
}

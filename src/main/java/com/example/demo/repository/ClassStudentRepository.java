package com.example.demo.repository;

import com.example.demo.entity.ClassStudent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClassStudentRepository extends JpaRepository<ClassStudent, String> {
    List<ClassStudent> findByClassIdAndStatusIgnoreCase(String classId, String status);
    List<ClassStudent> findByClassIdInAndStatusIgnoreCase(Collection<String> classIds, String status);
    List<ClassStudent> findByStudentId(String studentId);
    List<ClassStudent> findByStudentIdAndStatusIgnoreCase(String studentId, String status);
    Optional<ClassStudent> findByClassIdAndStudentId(String classId, String studentId);
    boolean existsByClassId(String classId);
    long countByClassIdAndStatusIgnoreCase(String classId, String status);
}

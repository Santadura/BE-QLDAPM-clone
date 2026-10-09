package com.example.demo.repository;

import com.example.demo.entity.StudentTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentTargetRepository extends JpaRepository<StudentTarget, String> {
    List<StudentTarget> findByStudentId(String studentId);
    List<StudentTarget> findByStudentIdAndCourseId(String studentId, String courseId);
}

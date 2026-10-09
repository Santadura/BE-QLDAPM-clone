package com.example.demo.repository;

import com.example.demo.entity.ClassAccessScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassAccessScopeRepository extends JpaRepository<ClassAccessScope, String> {
    List<ClassAccessScope> findByEmployeeIdAndStatusIgnoreCase(String employeeId, String status);
    boolean existsByEmployeeIdAndClassIdAndStatusIgnoreCase(String employeeId, String classId, String status);
}

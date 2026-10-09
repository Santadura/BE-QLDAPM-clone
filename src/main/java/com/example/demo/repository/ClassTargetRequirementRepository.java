package com.example.demo.repository;

import com.example.demo.entity.ClassTargetRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClassTargetRequirementRepository extends JpaRepository<ClassTargetRequirement, String> {
    List<ClassTargetRequirement> findByClassIdOrderByTargetTypeAsc(String classId);
    void deleteByClassId(String classId);
}

package com.example.demo.repository;

import com.example.demo.entity.TeachingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeachingScheduleRepository extends JpaRepository<TeachingSchedule, String> {
    List<TeachingSchedule> findByTeacherIdAndStatusIgnoreCase(String teacherId, String status);
    List<TeachingSchedule> findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(String classId, String status);
    boolean existsByTeacherIdAndClassIdAndStatusIgnoreCase(String teacherId, String classId, String status);
    boolean existsByClassId(String classId);
}

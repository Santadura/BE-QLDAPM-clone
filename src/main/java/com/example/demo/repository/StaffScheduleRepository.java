package com.example.demo.repository;

import com.example.demo.entity.StaffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, String> {
    List<StaffSchedule> findByClassIdAndStatusIgnoreCaseOrderByDateAscStartTimeAsc(String classId, String status);
    boolean existsByClassId(String classId);
}

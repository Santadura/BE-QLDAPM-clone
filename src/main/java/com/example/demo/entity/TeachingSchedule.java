package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "teaching_schedules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeachingSchedule {
    @Id
    @Column(name = "teaching_schedule_id", length = 50, nullable = false)
    private String teachingScheduleId;

    @Column(name = "teacher_id", length = 50, nullable = false)
    private String teacherId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "assigned_by", length = 50)
    private String assignedById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

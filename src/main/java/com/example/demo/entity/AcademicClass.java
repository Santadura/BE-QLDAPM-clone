package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "classes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcademicClass {
    @Id
    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "course_id", length = 50, nullable = false)
    private String courseId;

    @Column(name = "class_code", length = 30, nullable = false, unique = true)
    private String classCode;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_by", length = 50)
    private String createdByUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

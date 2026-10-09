package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "exams")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Exam {
    @Id
    @Column(name = "exam_id", length = 50, nullable = false)
    private String examId;

    @Column(name = "teacher_id", length = 50, nullable = false)
    private String teacherId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "duration", nullable = false)
    private Integer duration;

    @Column(name = "exam_date", nullable = false)
    private Instant examDate;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

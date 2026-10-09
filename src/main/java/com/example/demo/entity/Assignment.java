package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "assignments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Assignment {
    @Id
    @Column(name = "assignment_id", length = 50, nullable = false)
    private String assignmentId;

    @Column(name = "teacher_id", length = 50, nullable = false)
    private String teacherId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

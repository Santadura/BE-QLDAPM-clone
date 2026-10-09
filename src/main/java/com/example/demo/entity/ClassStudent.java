package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "class_students")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassStudent {
    @Id
    @Column(name = "class_student_id", length = 50, nullable = false)
    private String classStudentId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "student_id", length = 50, nullable = false)
    private String studentId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "inactive_reason", length = 80)
    private String inactiveReason;

    @Column(name = "inactivated_at")
    private Instant inactivatedAt;
}

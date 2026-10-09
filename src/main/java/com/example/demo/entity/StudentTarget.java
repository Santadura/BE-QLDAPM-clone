package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "student_targets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentTarget {
    @Id
    @Column(name = "student_target_id", length = 50, nullable = false)
    private String studentTargetId;

    @Column(name = "student_id", length = 50, nullable = false)
    private String studentId;

    @Column(name = "course_id", length = 50, nullable = false)
    private String courseId;

    @Column(name = "target_type", length = 40, nullable = false)
    private String targetType;

    @Column(name = "target_value", precision = 8, scale = 2, nullable = false)
    private BigDecimal targetValue;
}

package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "student_results")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentResult {
    @Id
    @Column(name = "student_result_id", length = 50, nullable = false)
    private String studentResultId;

    @Column(name = "student_id", length = 50, nullable = false)
    private String studentId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "assignment_id", length = 50)
    private String assignmentId;

    @Column(name = "exam_id", length = 50)
    private String examId;

    @Column(name = "score", precision = 5, scale = 2, nullable = false)
    private BigDecimal score;

    @Column(name = "feedback")
    private String feedback;

    @Column(name = "evaluated_by", length = 50, nullable = false)
    private String evaluatedById;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;
}

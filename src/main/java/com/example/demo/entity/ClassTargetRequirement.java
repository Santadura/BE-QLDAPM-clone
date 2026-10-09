package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "class_target_requirements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassTargetRequirement {
    @Id
    @Column(name = "class_target_requirement_id", length = 50, nullable = false)
    private String classTargetRequirementId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "target_type", length = 40, nullable = false)
    private String targetType;

    @Column(name = "required_target", precision = 8, scale = 2, nullable = false)
    private BigDecimal requiredTarget;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

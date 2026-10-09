package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "class_access_scopes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassAccessScope {
    @Id
    @Column(name = "class_access_scope_id", length = 50, nullable = false)
    private String classAccessScopeId;

    @Column(name = "employee_id", length = 50, nullable = false)
    private String employeeId;

    @Column(name = "class_id", length = 50, nullable = false)
    private String classId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "source", length = 30, nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

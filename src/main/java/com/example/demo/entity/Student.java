package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "students")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Student {
    @Id
    @Column(name = "student_id", length = 50, nullable = false)
    private String studentId;

    @Column(name = "student_code", length = 30, nullable = false, unique = true)
    private String studentCode;

    @Column(name = "full_name", length = 100, nullable = false)
    private String fullName;

    @Column(name = "phone", length = 15)
    private String phone;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "level", length = 30)
    private String level;

    @Column(name = "status", length = 20, nullable = false)
    private String status;
}

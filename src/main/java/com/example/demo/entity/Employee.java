package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "employees")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {
    @Id
    @Column(name = "employee_id", length = 50, nullable = false)
    private String employeeId;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "employee_code", length = 30, nullable = false, unique = true)
    private String employeeCode;

    @Column(name = "full_name", length = 100, nullable = false)
    private String fullName;

    @Column(name = "status", length = 20, nullable = false)
    private String status;
}

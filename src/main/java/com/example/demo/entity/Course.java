package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "courses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Course {
    @Id
    @Column(name = "course_id", length = 50, nullable = false)
    private String courseId;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "status", length = 20, nullable = false)
    private String status;
}

package com.example.demo.repository;

import com.example.demo.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    List<AuditLog> findTop50ByEntityTypeIgnoreCaseAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}

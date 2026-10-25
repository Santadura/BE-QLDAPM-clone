package com.example.demo.repository;

import com.example.demo.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {
    // JWT_SESSION records are durable revocation markers, not raw tokens.
    // Retain them at least until the corresponding JWTs have expired.
    boolean existsByEntityTypeAndEntityId(String entityType, String entityId);
    List<AuditLog> findTop50ByEntityTypeIgnoreCaseAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}

package vn.iig.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AuditEvent(
    @NotBlank @Size(max = 50) String eventId,
    @Size(max = 50) String username,
    @NotBlank @Size(max = 100) String action,
    @NotBlank @Size(max = 50) String entityType,
    @NotBlank @Size(max = 50) String entityId,
    String description,
    @NotNull Instant occurredAt
) {}

package ru.taska.domain;

import java.time.Instant;
import java.util.UUID;

/** Registry metadata and internal generation-aware deletion fence. */
public record ProjectIssueType(UUID projectId, String code, String name, String color, String icon, String shape,
                               int sortOrder, Instant createdAt, Instant updatedAt, UUID generation, UUID pendingOperationId) {
    public ProjectIssueType(UUID projectId, String code, String name, String color, String icon, String shape,
                            int sortOrder, Instant createdAt, Instant updatedAt, UUID generation) {
        this(projectId, code, name, color, icon, shape, sortOrder, createdAt, updatedAt, generation, null);
    }
    public ProjectIssueType(UUID projectId, String code, String name, String color, String icon, String shape,
                            int sortOrder, Instant createdAt, Instant updatedAt) {
        this(projectId, code, name, color, icon, shape, sortOrder, createdAt, updatedAt, null);
    }
}

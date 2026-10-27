package ru.taska.domain;

import java.time.Instant;
import java.util.UUID;

/** Registry entry; {@code icon} is optional and {@code shape} is square, circle or diamond (TAS-432). */
public record ProjectIssueType(UUID projectId, String code, String name, String color, String icon, String shape,
                               int sortOrder, Instant createdAt, Instant updatedAt, UUID generation) {
    public ProjectIssueType(UUID projectId, String code, String name, String color, String icon, String shape,
                            int sortOrder, Instant createdAt, Instant updatedAt) {
        this(projectId, code, name, color, icon, shape, sortOrder, createdAt, updatedAt, null);
    }
}

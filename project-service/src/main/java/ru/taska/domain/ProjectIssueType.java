package ru.taska.domain;

import java.time.Instant;
import java.util.UUID;

public record ProjectIssueType(UUID projectId, String code, String name, String color,
                               String icon, int sortOrder, Instant createdAt, Instant updatedAt) {
}

package ru.taska.domain.projection;

import org.springframework.data.relational.core.mapping.Column;

import java.util.UUID;

public record TargetIssue(
        @Column("id")
        UUID id,
        String issueKey,
        String summary,
        @Column("project_id")
        UUID projectId,
        String statusKey
) {}

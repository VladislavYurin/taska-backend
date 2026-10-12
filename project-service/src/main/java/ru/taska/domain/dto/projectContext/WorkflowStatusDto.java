package ru.taska.domain.dto.projectContext;

import lombok.Builder;

@Builder
public record WorkflowStatusDto(
        String id,
        String statusKey,
        String name,
        String category,
        int sortOrder
) {}
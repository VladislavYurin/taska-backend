package ru.taska.domain.dto.projectContext;

import lombok.Builder;

@Builder
public record WorkflowTransitionDto(
        String id,
        String fromStatusId,
        String toStatusId,
        String name,
        int sortOrder
) {}

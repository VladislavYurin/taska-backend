package ru.taska.domain.dto.projectContext;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder
public record WorkflowDto(
        String id,
        String name,
        int version,
        Instant createdAt,
        Instant updatedAt,
        List<WorkflowStatusDto> statuses,
        List<WorkflowTransitionDto> transitions
) {}
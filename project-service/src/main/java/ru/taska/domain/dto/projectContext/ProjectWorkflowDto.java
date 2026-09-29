package ru.taska.domain.dto.projectContext;

public record ProjectWorkflowDto(
        String issueType,
        WorkflowDto workflow
) {}

package ru.taska.dto;

public record WorkflowCreationViolation(WorkflowCreationViolationType type, String message) {
}

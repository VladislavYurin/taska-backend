package ru.taska.domain.dto;

/** Look of a project's issue type from the registry; {@code icon} is null when the type has none (TAS-432). */
public record IssueTypeMetadata(String name, String color, String icon, String shape) { }

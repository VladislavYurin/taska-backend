package ru.taska.domain.dto;

public record UnwatchIssueResult(
        boolean removed,
        long watchersCount
) {
}

package ru.taska.domain.dto;

public record IssueWatchStateDto(
        boolean watchedByMe,
        long watchersCount
) {
}

package ru.taska.domain.dto;

import ru.taska.domain.entity.IssueWatcher;

public record WatchIssueResult(
        IssueWatcher watcher,
        long watchersCount
) {
}

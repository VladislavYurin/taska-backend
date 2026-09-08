package ru.taska.domain.dto;

import ru.taska.domain.IssueWatcher;

public record WatchIssueResult(
        IssueWatcher watcher,
        long watchersCount
) {
}

package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssuePriority;
import ru.taska.domain.IssueStatus;
import ru.taska.domain.IssueType;
import ru.taska.domain.IssueWithHistory;
import ru.taska.domain.PageResult;

import java.util.UUID;

public interface IssueService {

    Mono<Issue> createIssue(
            UUID projectId,
            IssueType issueType,
            String summary,
            String description,
            IssuePriority priority,
            UUID reporterId
    );

    Mono<Issue> assignIssue(UUID issueId, UUID assigneeId, UUID actorUserId);


    Mono<IssueWithHistory> getIssue(UUID issueId);

    Mono<PageResult<Issue>> listIssues(UUID projectId, IssueStatus status, UUID assigneeId, Integer page, Integer pageSize);

    Mono<Issue> deleteIssue(String requestId, String nodeId, UUID issueId, UUID actorUserId);
}

package ru.taska.repository;

import reactor.core.publisher.Flux;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueStatus;

import java.util.UUID;

public interface IssueRepositoryCustom {

    Flux<Issue> findByFilter(UUID projectId, IssueStatus status, UUID assigneeId);
}

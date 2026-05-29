package ru.taska.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueStatus;

import java.util.UUID;

public interface IssueRepositoryCustom {

    Flux<Issue> findByFilter(UUID projectId, IssueStatus status, UUID assigneeId, int limit, long offset);

    Mono<Long> countByFilter(UUID projectId, IssueStatus status, UUID assigneeId);
}

package ru.taska.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;

import java.util.UUID;

public interface IssueRepositoryCustom {

    Flux<Issue> findByFilter(UUID projectId, String status, UUID assigneeId, int limit, long offset);

    Mono<Long> countByFilter(UUID projectId, String status, UUID assigneeId);
}

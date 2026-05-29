package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;

import java.util.UUID;

public interface IssueRepository extends ReactiveCrudRepository<Issue, UUID>, IssueRepositoryCustom {

    Mono<Issue> findByIdAndDeletedAtIsNull(UUID id);
}

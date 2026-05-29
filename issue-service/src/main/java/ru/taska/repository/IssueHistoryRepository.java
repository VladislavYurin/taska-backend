package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import ru.taska.domain.IssueHistory;

import java.util.UUID;

public interface IssueHistoryRepository extends ReactiveCrudRepository<IssueHistory, UUID> {

    Flux<IssueHistory> findByIssueIdOrderByOccurredAtDesc(UUID issueId);
}

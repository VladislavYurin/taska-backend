package ru.taska.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import ru.taska.domain.IssueHistory;

import java.util.UUID;

public interface IssueHistoryRepository extends ReactiveCrudRepository<IssueHistory, UUID> {
}

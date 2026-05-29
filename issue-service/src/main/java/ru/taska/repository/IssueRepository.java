package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.Issue;

import java.util.UUID;

public interface IssueRepository extends ReactiveCrudRepository<Issue, UUID>, IssueRepositoryCustom {

    @Query("SELECT * FROM taska.issues WHERE id = :id AND deleted_at IS NULL")
    Mono<Issue> findActiveById(@Param("id") UUID id);

    Mono<Issue> findByIdAndDeletedAtIsNull(UUID id);
}

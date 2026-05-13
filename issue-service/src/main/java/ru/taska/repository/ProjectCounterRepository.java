package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectCounter;

import java.util.UUID;

public interface ProjectCounterRepository extends ReactiveCrudRepository<ProjectCounter, UUID> {

    @Query("""
              INSERT INTO taska.project_counters (project_id, next_issue_number)
              VALUES (:projectId, 2)
              ON CONFLICT (project_id) DO UPDATE
                  SET next_issue_number = taska.project_counters.next_issue_number + 1
              RETURNING next_issue_number - 1
              """)
    Mono<Integer> getNextIssueNumberAndIncrement(UUID projectId);
}

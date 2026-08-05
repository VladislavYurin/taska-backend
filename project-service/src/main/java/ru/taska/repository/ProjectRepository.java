package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;


@Repository
public interface ProjectRepository extends R2dbcRepository<Project, UUID> {
    @Query("SELECT p.* FROM taska.projects p JOIN taska.project_members pm ON p.id = pm.project_id WHERE pm.user_id = :userId")
    Flux<Project> findAllByMemberUserId(UUID userId);

    Mono<Project> findByProjectKey(String projectKey);

    @Query("SELECT p.* FROM taska.projects p JOIN taska.project_members pm ON p.id = pm.project_id WHERE p.id = :projectId AND pm.user_id = :userId")
    Mono<Project> findByProjectIdAndUserId(UUID projectId,UUID userId);
}

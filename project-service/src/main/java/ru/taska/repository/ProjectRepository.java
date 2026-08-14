package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;

import java.util.UUID;
import ru.taska.domain.Project;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

@Repository
public interface ProjectRepository extends R2dbcRepository<Project, UUID> {
    @Query("SELECT p.* FROM taska.projects p JOIN taska.project_members pm ON p.id = pm.project_id WHERE pm.user_id = :userId")
    Flux<Project> findAllByMemberUserId(UUID userId);

    Mono<Project> findByProjectKey(String projectKey);
}

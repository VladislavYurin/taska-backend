package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;


@Repository
public interface ProjectRepository extends R2dbcRepository<Project, UUID> {
    @Query("SELECT p.* FROM taska.projects p JOIN taska.project_members pm ON p.id = pm.project_id WHERE pm.user_id = :userId")
    Flux<Project> findAllByMemberUserId(UUID userId);

    Mono<Project> findByProjectKey(String projectKey);

    @Query("SELECT p.id as project_id, p.project_key as project_key, p.name, " +
            "p.created_by as created_by, p.created_at as created_at, " +
            "p.updated_at as updated_at, p.archived_at as archived_at, " +
            "pm.user_id as user_id " +
            "FROM taska.projects p LEFT JOIN taska.project_members pm ON p.id = pm.project_id AND pm.user_id = :userId WHERE p.id = :projectId")
    Mono<ProjectCheckMembershipDto> findProjectMemberShipDtoByProjectIdAndUserId(UUID projectId, UUID userId);
}

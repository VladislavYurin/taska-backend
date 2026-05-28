package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import ru.taska.entity.Project;

import java.util.UUID;

@Repository
public interface ProjectRepository extends ReactiveCrudRepository<Project, UUID> {

    @Query("SELECT p.* FROM projects p JOIN project_members pm ON p.id = pm.project_id WHERE pm.user_id = :userId")
    Flux<Project> findAllByMemberUserId(UUID userId);
}

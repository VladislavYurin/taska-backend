package ru.taska.repository;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.ProjectSetting;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ProjectSettingRepository extends ReactiveCrudRepository<ProjectSetting, UUID> {

    @Modifying
    @Query("""
        INSERT INTO taska.project_settings (project_id, settings, updated_at, updated_by)
        VALUES (:projectId, :settings::jsonb, :updatedAt, :updatedBy)
        """)
    Mono<Void> insertSetting(UUID projectId, String settings, Instant updatedAt, UUID updatedBy);
}
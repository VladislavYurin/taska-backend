package ru.taska.repository.labels;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.labels.ProjectLabels;

import java.util.UUID;

@Repository
public interface ProjectLabelsRepository extends R2dbcRepository<ProjectLabels, UUID> {

    // Получить все активные метки проекта
    Flux<ProjectLabels> findByProjectIdAndDeletedAtIsNull(UUID projectId);

    // Получить метку по ID (только активную)
    Mono<ProjectLabels> findByIdAndDeletedAtIsNull(UUID id);

    // Проверить существование активной метки с таким именем в проекте
    @Query("SELECT EXISTS(" +
            "SELECT 1 FROM taska.project_labels " +
            "WHERE project_id = :projectId " +
            "AND name = :name " +
            "AND deleted_at IS NULL" +
            ")")
    Mono<Boolean> existsActiveByName(UUID projectId, String name);

    // Найти активную метку по имени
    @Query("SELECT * FROM taska.project_labels " +
            "WHERE project_id = :projectId " +
            "AND LOWER(TRIM(name)) = LOWER(TRIM(:name)) " +
            "AND deleted_at IS NULL")
    Mono<ProjectLabels> findActiveByName(UUID projectId, String name);

    // Мягкое удаление
    @Query("UPDATE taska.project_labels SET deleted_at = NOW() " +
            "WHERE id = :id AND deleted_at IS NULL")
    Mono<Void> softDelete(UUID id);


    @Query("SELECT * FROM taska.project_labels WHERE project_id = :projectId AND deleted_at IS NULL")
    Flux<ProjectLabels> findAllActiveByProjectId(UUID projectId);

}

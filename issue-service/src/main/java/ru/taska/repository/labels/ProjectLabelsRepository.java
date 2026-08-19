package ru.taska.repository.labels;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.labels.ProjectLabels;

import java.util.UUID;

/**
 * Репозиторий для работы с метками проекта.
 */
public interface ProjectLabelsRepository extends R2dbcRepository<ProjectLabels, UUID> {

    /**
     * Находит все активные метки проекта.
     *
     * @param projectId идентификатор проекта
     * @return Flux<{@link ProjectLabels}> поток активных меток проекта
     */
    Flux<ProjectLabels> findByProjectIdAndDeletedAtIsNull(UUID projectId);

    /**
     * Находит активную метку по идентификатору.
     * Возвращает метку только если она не была удалена (deleted_at IS NULL).
     *
     * @param id идентификатор метки
     * @return Mono<{@link ProjectLabels}> найденная метка или пустой Mono
     */
    Mono<ProjectLabels> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Проверяет существование активной метки с указанным именем в проекте.
     *
     * @param projectId идентификатор проекта
     * @param name      название метки для проверки
     * @return Mono<Boolean> true если метка с таким именем существует, иначе false
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM taska.project_labels
            WHERE project_id = :projectId
            AND name = :name
            AND deleted_at IS NULL
            )
        """)
    Mono<Boolean> existsActiveByName(UUID projectId, String name);

    /**
     * Находит активную метку по названию в проекте.
     *
     * @param projectId идентификатор проекта
     * @param name      название метки для поиска
     * @return Mono<{@link ProjectLabels}> найденная метка или пустой Mono
     */
    @Query("""
            SELECT * FROM taska.project_labels
            WHERE project_id = :projectId
            AND name = :name
            AND deleted_at IS NULL
            """)
    Mono<ProjectLabels> findActiveByName(UUID projectId, String name);

    /**
     * Выполняет мягкое удаление метки проекта.
     * Устанавливает текущую временную метку в поле deleted_at.
     * Обновление выполняется только если метка ещё не была удалена.
     *
     * @param id идентификатор метки для удаления
     * @return Mono<Void> сигнал завершения операции
     */
    @Query("""
            UPDATE taska.project_labels SET deleted_at = NOW()
            WHERE id = :id AND deleted_at IS NULL
            """)
    Mono<Void> softDelete(UUID id);
}
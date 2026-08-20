package ru.taska.repository.labels;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;

import java.util.UUID;

/**
 * Репозиторий для работы со связями задач и меток.
 */
public interface IssueLabelsRepository extends R2dbcRepository<IssueLabels, UUID> {

    /**
     * Проверяет наличие связи между задачей и меткой.
     *
     * @param issueId идентификатор задачи
     * @param labelId идентификатор метки
     * @return Mono<Boolean> true если связь существует, иначе false
     */
    Mono<Boolean> existsByIssueIdAndLabelId(UUID issueId, UUID labelId);

    /**
     * Удаляет связь между задачей и меткой.
     * Выполняет физическое удаление записи из таблицы issue_labels.
     *
     * @param issueId идентификатор задачи
     * @param labelId идентификатор метки
     * @return Mono<Void> сигнал завершения операции
     */
    Mono<Void> deleteByIssueIdAndLabelId(UUID issueId, UUID labelId);

    /**
     * Находит все активные метки, привязанные к задаче.
     * Выполняет JOIN с таблицей project_labels для получения полной информации о метках.
     * Возвращает только активные метки (deleted_at IS NULL).
     *
     * @param issueId идентификатор задачи
     * @return Flux<{@link ProjectLabels}> поток активных меток задачи
     */
    @Query("""
        SELECT pl.* FROM taska.project_labels pl
        JOIN taska.issue_labels il ON il.label_id = pl.id
        WHERE il.issue_id = :issueId
        AND pl.deleted_at IS NULL
    """)
    Flux<ProjectLabels> findActiveLabelsByIssueId(UUID issueId);
}
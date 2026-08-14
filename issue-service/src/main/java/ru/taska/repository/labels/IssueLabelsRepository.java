package ru.taska.repository.labels;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;

import java.util.UUID;

@Repository
public interface IssueLabelsRepository extends R2dbcRepository<IssueLabels, UUID> {

    // Проверить наличие связи
    Mono<Boolean> existsByIssueIdAndLabelId(UUID issueId, UUID labelId);

    // Удалить связь
    @Query("DELETE FROM taska.issue_labels WHERE issue_id = :issueId AND label_id = :labelId")
    Mono<Void> deleteByIssueIdAndLabelId(UUID issueId, UUID labelId);

    // Получить все метки для задачи (с JOIN)
    @Query("""
        SELECT pl.* FROM taska.project_labels pl
        JOIN taska.issue_labels il ON il.label_id = pl.id
        WHERE il.issue_id = :issueId 
        AND pl.deleted_at IS NULL
    """)
    Flux<ProjectLabels> findLabelsByIssueId(UUID issueId);

}

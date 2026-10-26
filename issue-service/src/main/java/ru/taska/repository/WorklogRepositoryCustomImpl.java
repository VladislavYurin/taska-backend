package ru.taska.repository;

import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.taska.domain.aggregate.WorklogRows;
import ru.taska.domain.entity.Worklog;

@Repository
@RequiredArgsConstructor
public class WorklogRepositoryCustomImpl implements WorklogRepositoryCustom {
  private final R2dbcEntityTemplate template;

  private record RowValue(Worklog worklog, long total) {}

  @Override
  public Mono<WorklogRows> findActivePageWithCount(UUID issueId, int pageSize, long offset) {
    return template
        .getDatabaseClient()
        .sql(
            """
            WITH counted AS (
                SELECT w.*, count(*) OVER () AS total_count
                FROM taska.issue_worklogs w WHERE issue_id=:issueId AND deleted_at IS NULL
            ), selected AS (
                SELECT * FROM counted ORDER BY work_date DESC, created_at DESC, id DESC
                LIMIT :pageSize OFFSET :offset
            ), total AS (
                SELECT coalesce(max(total_count),0) AS matched_total FROM counted
            )
            SELECT selected.*, total.matched_total FROM total LEFT JOIN selected ON true
            ORDER BY selected.work_date DESC, selected.created_at DESC, selected.id DESC
            """)
        .bind("issueId", issueId)
        .bind("pageSize", pageSize)
        .bind("offset", offset)
        .map(
            (row, metadata) ->
                new RowValue(
                    row.get("id", UUID.class) == null
                        ? null
                        : template.getConverter().read(Worklog.class, row, metadata),
                    Objects.requireNonNull(row.get("matched_total", Long.class))))
        .all()
        .collectList()
        .map(
            rows ->
                new WorklogRows(
                    rows.stream().map(RowValue::worklog).filter(Objects::nonNull).toList(),
                    rows.isEmpty() ? 0 : rows.getFirst().total()));
  }
}

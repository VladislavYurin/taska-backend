package ru.taska.issuestorage;

import io.r2dbc.spi.Row;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import ru.taska.issuestorage.TenantIssueQueries.Board;
import ru.taska.issuestorage.TenantIssueQueries.BoardPage;
import ru.taska.issuestorage.TenantIssueQueries.Card;
import ru.taska.issuestorage.TenantIssueQueries.Filter;
import ru.taska.issuestorage.TenantIssueQueries.Page;
import ru.taska.issuestorage.TenantIssueQueries.Search;
import ru.taska.issuestorage.TenantIssueQueries.Workflow;

/** Только параметризованные запросы; scope применяется до count/window/LIMIT в одном snapshot. */
final class TenantIssueQuerySql {
  private record Result(
      TenantIssueStore.Snapshot issue, long total, long statusTotal, long remaining) {}

  private final DatabaseClient db;
  private final UUID tenant;
  private final Function<Row, TenantIssueStore.Snapshot> snapshot;
  private final Map<String, Object> bindings = new LinkedHashMap<>();

  TenantIssueQuerySql(
      DatabaseClient db, UUID tenant, Function<Row, TenantIssueStore.Snapshot> snapshot) {
    this.db = db;
    this.tenant = tenant;
    this.snapshot = snapshot;
  }

  Mono<Page> search(Search q) {
    String filtered = filtered(q.projects(), q.filter());
    bindings.put("size", q.size());
    bindings.put("offset", (long) q.page() * q.size());
    String sql =
        "WITH filtered AS MATERIALIZED ("
            + filtered
            + "), page AS (SELECT * FROM filtered ORDER BY created_at DESC,id DESC LIMIT :size"
            + " OFFSET :offset) SELECT totals.total,page.* FROM (SELECT count(*) total FROM"
            + " filtered) totals LEFT JOIN page ON true ORDER BY page.created_at DESC,page.id DESC";
    return query(sql)
        .map((r, m) -> result(r, false))
        .all()
        .collectList()
        .map(
            rows ->
                new Page(
                    rows.stream().filter(r -> r.issue() != null).map(Result::issue).toList(),
                    rows.getFirst().total()));
  }

  Mono<BoardPage> board(Board q, Workflow w) {
    String filtered = filtered(List.of(q.project()), q.filter());
    bindings.put("statuses", w.statuses());
    filtered += " AND status_key IN (:statuses)";
    String done = "false";
    if (!w.done().isEmpty()) {
      bindings.put("done", w.done());
      done = "status_key IN (:done)";
      if (!q.includeDone()) filtered += " AND NOT (" + done + ")";
      if (q.doneSince() != null) {
        bindings.put("since", OffsetDateTime.ofInstant(q.doneSince(), ZoneOffset.UTC));
        filtered += " AND (NOT (" + done + ") OR updated_at>=:since)";
      }
    }
    String cursor = "";
    if (q.after() != null) {
      bindings.put("at", OffsetDateTime.ofInstant(q.after().at(), ZoneOffset.UTC));
      bindings.put("after", q.after().id());
      cursor =
          w.done().contains(q.filter().status())
              ? " WHERE (updated_at,id)<(:at,:after)"
              : " WHERE (created_at,id)>(:at,:after)";
    }
    bindings.put("limit", q.perColumn());
    String order =
        "CASE WHEN "
            + done
            + " THEN updated_at END DESC, CASE WHEN NOT ("
            + done
            + ") THEN created_at END ASC, "
            + "CASE WHEN "
            + done
            + " THEN id END DESC, CASE WHEN NOT ("
            + done
            + ") THEN id END ASC";
    String sql =
        "WITH filtered AS MATERIALIZED ("
            + filtered
            + "), totals AS (SELECT *,count(*) OVER(PARTITION BY issue_type,status_key)"
            + " status_total FROM filtered), ranked AS (SELECT *,count(*) OVER(PARTITION BY"
            + " issue_type,status_key) remaining,row_number() OVER(PARTITION BY"
            + " issue_type,status_key ORDER BY "
            + order
            + ") position FROM totals"
            + cursor
            + ") SELECT size.total,page.* FROM (SELECT count(*) total FROM filtered) size LEFT JOIN"
            + " (SELECT * FROM ranked WHERE position<=:limit) page ON true ORDER BY"
            + " page.issue_type,page.status_key,page.position";
    return query(sql)
        .map((r, m) -> result(r, true))
        .all()
        .collectList()
        .map(
            rows ->
                new BoardPage(
                    rows.stream()
                        .filter(r -> r.issue() != null)
                        .map(
                            r ->
                                new Card(r.issue(), r.statusTotal(), r.remaining() > q.perColumn()))
                        .toList(),
                    rows.getFirst().total()));
  }

  private Result result(Row r, boolean board) {
    boolean present = r.get("id", UUID.class) != null;
    return new Result(
        present ? snapshot.apply(r) : null,
        r.get("total", Long.class),
        present && board ? r.get("status_total", Long.class) : 0,
        present && board ? r.get("remaining", Long.class) : 0);
  }

  private String filtered(List<UUID> projects, Filter f) {
    bindings.put("tenant", tenant);
    bindings.put("projects", projects);
    String sql =
        "SELECT * FROM taska.issues WHERE tenant_id=:tenant AND project_id IN (:projects) AND"
            + " deleted_at IS NULL";
    sql = condition(sql, "issue_type", "type", f.type());
    sql = condition(sql, "status_key", "status", f.status());
    sql = condition(sql, "priority", "priority", f.priority());
    sql = condition(sql, "assignee_id", "assignee", f.assignee());
    sql = condition(sql, "reporter_id", "reporter", f.reporter());
    if (f.text() != null) {
      sql +=
          " AND (issue_key ILIKE :text ESCAPE '!' OR summary ILIKE :text ESCAPE '!' OR description"
              + " ILIKE :text ESCAPE '!')";
      bindings.put(
          "text", "%" + f.text().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    return sql;
  }

  private String condition(String sql, String column, String name, Object value) {
    if (value == null) return sql;
    bindings.put(name, value);
    return sql + " AND " + column + "=:" + name;
  }

  private DatabaseClient.GenericExecuteSpec query(String sql) {
    var q = db.sql(sql);
    for (var b : bindings.entrySet()) q = q.bind(b.getKey(), b.getValue());
    return q;
  }
}

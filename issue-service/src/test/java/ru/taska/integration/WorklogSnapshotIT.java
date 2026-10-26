package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.repository.IssueRepository;
import ru.taska.repository.WorklogRepository;
import ru.taska.repository.labels.IssueLabelsRepository;

class WorklogSnapshotIT extends AbstractIT {
  @Autowired WorklogRepository worklogs;
  @Autowired IssueRepository issues;
  @Autowired IssueLabelsRepository labels;
  @Autowired DatabaseClient database;
  private UUID issueId, projectId;
  private static final UUID AUTHOR = UUID.randomUUID();

  @BeforeEach
  void prepare() {
    issueId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    database
        .sql(
            """
INSERT INTO taska.issues(id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id,version)
VALUES(:id,:project,1,:key,'TASK','Snapshot','TODO','MEDIUM',:actor,1)
""")
        .bind("id", issueId)
        .bind("project", projectId)
        .bind("key", "SNAP-" + issueId)
        .bind("actor", AUTHOR)
        .then()
        .block();
  }

  private Mono<Void> insert(UUID id) {
    return database
        .sql(
            """
            INSERT INTO taska.issue_worklogs
            (id,issue_id,project_id,author_user_id,spent_minutes,work_date,created_at)
            VALUES (:id,:issue,:project,:author,30,'2026-10-26','2026-10-26T00:00:00Z')
            """)
        .bind("id", id)
        .bind("issue", issueId)
        .bind("project", projectId)
        .bind("author", AUTHOR)
        .then();
  }

  @Test
  void countOrderSoftDeleteAndOutOfRangePageShareOneStatement() {
    var first = UUID.fromString("00000000-0000-4000-8000-000000000001");
    var second = UUID.fromString("00000000-0000-4000-8000-000000000002");
    var third = UUID.fromString("00000000-0000-4000-8000-000000000003");
    var deleted = UUID.randomUUID();
    insert(first)
        .then(insert(second))
        .then(insert(third))
        .then(insert(deleted))
        .then(
            database
                .sql("UPDATE taska.issue_worklogs SET deleted_at=now() WHERE id=:id")
                .bind("id", deleted)
                .then())
        .block();
    var page = worklogs.findActivePageWithCount(issueId, 2, 0).block();
    assertThat(page.totalCount()).isEqualTo(3);
    assertThat(page.items()).extracting(w -> w.getId()).containsExactly(third, second);
    var last = worklogs.findActivePageWithCount(issueId, 2, 2).block();
    assertThat(last.items()).extracting(w -> w.getId()).containsExactly(first);
    assertThat(last.totalCount()).isEqualTo(3);
    var beyond = worklogs.findActivePageWithCount(issueId, 2, 100).block();
    assertThat(beyond.items()).isEmpty();
    assertThat(beyond.totalCount()).isEqualTo(3);
    assertThat(worklogs.findActivePageWithCount(UUID.randomUUID(), 2, 0).block().totalCount())
        .isZero();
  }

  @Test
  void concurrentInsertDeleteCannotMismatchCompletePageAndCount() {
    insert(UUID.randomUUID()).block();
    var writer =
        Flux.range(0, 20)
            .concatMap(
                n -> {
                  UUID id = UUID.randomUUID();
                  return insert(id)
                      .then(
                          database
                              .sql("DELETE FROM taska.issue_worklogs WHERE id=:id")
                              .bind("id", id)
                              .then());
                })
            .then();
    var reader =
        Flux.range(0, 40)
            .flatMap(
                n ->
                    worklogs
                        .findActivePageWithCount(issueId, 100, 0)
                        .doOnNext(
                            page -> assertThat(page.totalCount()).isEqualTo(page.items().size())),
                4)
            .then();
    Mono.when(writer, reader).block(Duration.ofSeconds(30));
  }

  @Test
  void boardAndIssueLabelReadersUseNameThenIdAndExcludeDeletedLabels() {
    UUID alpha = UUID.randomUUID(), zeta = UUID.randomUUID(), deleted = UUID.randomUUID();
    for (var entry :
        List.of(
            java.util.Map.entry(zeta, "zeta"),
            java.util.Map.entry(alpha, "alpha"),
            java.util.Map.entry(deleted, "deleted"))) {
      database
          .sql(
              "INSERT INTO taska.project_labels(id,project_id,name,color,created_by)"
                  + " VALUES(:id,:project,:name,'#112233',:author)")
          .bind("id", entry.getKey())
          .bind("project", projectId)
          .bind("name", entry.getValue())
          .bind("author", AUTHOR)
          .then()
          .block();
      database
          .sql(
              "INSERT INTO taska.issue_labels(issue_id,label_id,created_by)"
                  + " VALUES(:issue,:label,:author)")
          .bind("issue", issueId)
          .bind("label", entry.getKey())
          .bind("author", AUTHOR)
          .then()
          .block();
    }
    database
        .sql("UPDATE taska.project_labels SET deleted_at=now() WHERE id=:id")
        .bind("id", deleted)
        .then()
        .block();
    var board = issues.findLabelIdsByIssueIds(List.of(issueId)).block();
    assertThat(board.get(issueId)).containsExactly(alpha, zeta);
    assertThat(
            labels
                .findActiveLabelsByIssueId(issueId)
                .map(label -> label.getId())
                .collectList()
                .block())
        .containsExactly(alpha, zeta);
  }
}

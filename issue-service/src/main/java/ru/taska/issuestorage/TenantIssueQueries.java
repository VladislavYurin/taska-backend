package ru.taska.issuestorage;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** SQL-ядро чтения без directory/enrichment. Публичные RPC остаются закрыты до TAS-321. */
public final class TenantIssueQueries {
  public record Filter(
      String text, String type, String status, UUID assignee, UUID reporter, String priority) {
    public Filter {
      if (text != null && text.length() > 500
          || type != null && !type.matches("[A-Z][A-Z0-9_]{1,31}")
          || status != null && (status.isBlank() || status.length() > 100)
          || priority != null && !Set.of("LOW", "MEDIUM", "HIGH").contains(priority))
        throw invalid();
      text = text == null || text.isBlank() ? null : text.strip();
    }

    public static Filter all() {
      return new Filter(null, null, null, null, null, null);
    }
  }

  public record Search(List<UUID> projects, Filter filter, int page, int size) {
    public Search {
      projects = scope(projects);
      java.util.Objects.requireNonNull(filter);
      if (page < 0 || size < 1 || size > 100) throw invalid();
    }
  }

  /** Категории получены от workflow authority, а не из done_status_keys клиента. */
  public record Workflow(Set<String> statuses, Set<String> done) {
    public Workflow {
      statuses = Set.copyOf(statuses);
      done = Set.copyOf(done);
      if (statuses.isEmpty()
          || statuses.size() > 100
          || !statuses.containsAll(done)
          || statuses.stream().anyMatch(s -> s.isBlank() || s.length() > 100)) throw invalid();
    }
  }

  public record Cursor(Instant at, UUID id) {
    public Cursor {
      java.util.Objects.requireNonNull(at);
      java.util.Objects.requireNonNull(id);
    }
  }

  public record Board(
      UUID project,
      Filter filter,
      boolean includeDone,
      int perColumn,
      Instant doneSince,
      Cursor after) {
    public Board {
      java.util.Objects.requireNonNull(project);
      java.util.Objects.requireNonNull(filter);
      if (filter.type() == null
          || perColumn < 1
          || perColumn > 100
          || after != null && filter.status() == null) throw invalid();
    }
  }

  public record Page(List<TenantIssueStore.Snapshot> issues, long total) {
    public Page {
      issues = List.copyOf(issues);
    }
  }

  public record Card(TenantIssueStore.Snapshot issue, long statusTotal, boolean hasMore) {}

  public record BoardPage(List<Card> issues, long total) {
    public BoardPage {
      issues = List.copyOf(issues);
    }
  }

  private final TenantIssueStore store;
  private final TenantIssueAccess access;

  public TenantIssueQueries(TenantIssueStore store, TenantIssueAccess access) {
    this.store = store;
    this.access = access;
  }

  public Mono<Page> search(Search q) {
    return batch(q.projects(), "searchIssues").flatMap(b -> store.execute(s -> s.search(q, b)));
  }

  public Mono<BoardPage> board(Board q) {
    return batch(List.of(q.project()), "listIssuesForBoard")
        .flatMap(
            b ->
                access
                    .boardWorkflow(q.project(), q.filter().type())
                    .switchIfEmpty(
                        Mono.error(
                            new DomainException(DomainStatus.UNAVAILABLE, "WORKFLOW_UNAVAILABLE")))
                    .flatMap(w -> store.execute(s -> s.board(q, w, b))));
  }

  private Mono<TenantIssueAccess.ReadBatch> batch(List<UUID> projects, String action) {
    return ru.taska.security.tenant.VerifiedTenantContext.current()
        .onErrorMap(
            IllegalStateException.class,
            e ->
                new DomainException(
                    DomainStatus.UNAUTHENTICATED, "VERIFIED_QUERY_CONTEXT_REQUIRED"))
        .flatMap(
            c ->
                c.action().equals(action)
                    ? Mono.defer(() -> access.readMany(projects))
                    : Mono.error(
                        new DomainException(
                            DomainStatus.PERMISSION_DENIED, "QUERY_ACTION_MISMATCH")))
        .switchIfEmpty(
            Mono.error(
                new DomainException(DomainStatus.UNAVAILABLE, "QUERY_AUTHORITY_UNAVAILABLE")))
        .map(
            b -> {
              checkScope(projects, b);
              return b;
            });
  }

  static void checkScope(List<UUID> projects, TenantIssueAccess.ReadBatch batch) {
    var permitted = batch.permits().stream().map(TenantIssueAccess.Permit::project).toList();
    if (permitted.size() != projects.size() || !Set.copyOf(permitted).equals(Set.copyOf(projects)))
      throw new DomainException(DomainStatus.UNAVAILABLE, "INVALID_QUERY_AUTHORITY");
  }

  static List<UUID> scope(List<UUID> projects) {
    projects = List.copyOf(projects);
    if (projects.isEmpty()
        || projects.size() > 100
        || Set.copyOf(projects).size() != projects.size()) throw invalid();
    return projects;
  }

  static DomainException invalid() {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, "INVALID_ISSUE_QUERY");
  }
}

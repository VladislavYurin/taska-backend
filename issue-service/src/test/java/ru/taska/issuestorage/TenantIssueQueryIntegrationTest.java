package ru.taska.issuestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static ru.taska.issuestorage.TenantIssueStorageIntegrationTest.A;
import static ru.taska.issuestorage.TenantIssueStorageIntegrationTest.ACTOR;
import static ru.taska.issuestorage.TenantIssueStorageIntegrationTest.B;
import static ru.taska.issuestorage.TenantIssueStorageIntegrationTest.PROJECT;
import static ru.taska.issuestorage.TenantIssueStorageIntegrationTest.WAIT;
import static ru.taska.security.tenant.IssueStorageTestContext.as;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.issuestorage.TenantIssueQueries.Board;
import ru.taska.issuestorage.TenantIssueQueries.Filter;
import ru.taska.issuestorage.TenantIssueQueries.Search;
import ru.taska.security.tenant.VerifiedTenantContext;

@Testcontainers
@Timeout(90)
class TenantIssueQueryIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = TenantIssueStorageIntegrationTest.pg;
  final TenantIssueStorageIntegrationTest f = new TenantIssueStorageIntegrationTest();
  final UUID hidden = UUID.randomUUID();
  TenantIssueQueries queries;
  boolean allowed = true, audit = false;
  Set<String> done = Set.of("RESOLVED");

  @BeforeAll
  static void setup() throws Exception {
    TenantIssueStorageIntegrationTest.setup();
  }

  @AfterAll
  static void close() {
    TenantIssueStorageIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    f.reset();
    f.businessBoundary();
    queries = new TenantIssueQueries(TenantIssueStorageIntegrationTest.store, f.access);
    when(f.access.readMany(anyList()))
        .thenAnswer(
            call ->
                VerifiedTenantContext.current()
                    .flatMap(
                        c -> {
                          List<UUID> ids = call.getArgument(0);
                          if (!allowed || !ids.stream().allMatch(PROJECT::equals))
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.PERMISSION_DENIED, "CURRENT_PROJECT_ACL_DENIED"));
                          return Mono.just(
                              new TenantIssueAccess.ReadBatch(
                                  ids.stream().map(p -> permit(c, p)).toList(), 50L, false));
                        }));
    when(f.access.boardWorkflow(PROJECT, "TASK"))
        .thenAnswer(
            call ->
                Mono.just(
                    new TenantIssueQueries.Workflow(Set.of("TODO", "DONE", "RESOLVED"), done)));
  }

  TenantIssueAccess.Permit permit(VerifiedTenantContext c, UUID p) {
    return new TenantIssueAccess.Permit(
        UUID.fromString(c.tenantId()),
        UUID.fromString(c.actorId()),
        c.action(),
        p,
        f.expiry,
        false,
        audit,
        audit ? Set.of("READ_ONLY") : Set.of());
  }

  void sql(String s) {
    TenantIssueStorageIntegrationTest.sql(s);
  }

  TenantIssueStore.Created seed(UUID tenant, UUID project, String title) {
    return f.create(
            tenant,
            ACTOR,
            "createIssue",
            UUID.randomUUID().toString(),
            new TenantIssueStore.CreateIssue(
                project,
                project.equals(PROJECT) ? "ABC" : "HID",
                "TASK",
                title,
                "private description",
                "TODO",
                "MEDIUM",
                null,
                null,
                null,
                null,
                null,
                null))
        .block(WAIT);
  }

  TenantIssueQueries.Page search(Filter filter, int page, int size) {
    return as(
            A,
            ACTOR,
            "searchIssues",
            queries.search(new Search(List.of(PROJECT), filter, page, size)))
        .block(WAIT);
  }

  TenantIssueQueries.BoardPage board(
      String status, boolean include, int size, Instant since, TenantIssueQueries.Cursor cursor) {
    return as(
            A,
            ACTOR,
            "listIssuesForBoard",
            queries.board(
                new Board(
                    PROJECT,
                    new Filter(null, "TASK", status, null, null, null),
                    include,
                    size,
                    since,
                    cursor)))
        .block(WAIT);
  }

  DomainException denied(Mono<?> m, String action) {
    return assertThrows(DomainException.class, () -> as(A, ACTOR, action, m).block(WAIT));
  }

  @Test
  void searchScopesBeforeCountAndPaginationAcrossTenantAndProjects() {
    var a = seed(A, PROJECT, "visible");
    seed(B, PROJECT, "foreign tenant");
    seed(A, hidden, "hidden project");
    var page = search(Filter.all(), 0, 1);
    assertEquals(1, page.total());
    assertEquals(a.id(), page.issues().getFirst().identity().id());
    assertEquals(1, search(Filter.all(), 9, 1).total());
    assertTrue(search(Filter.all(), 9, 1).issues().isEmpty());
    var b =
        as(
                B,
                ACTOR,
                "searchIssues",
                queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)))
            .block(WAIT);
    assertEquals(1, b.total());
    assertEquals("foreign tenant", b.issues().getFirst().summary());
  }

  @Test
  void allNativeFiltersAndUnknownValuesKeepTotalsScoped() {
    var row = seed(A, PROJECT, "alpha");
    seed(A, PROJECT, "beta");
    sql(
        "UPDATE taska.issues SET assignee_id='"
            + ACTOR
            + "',status_key='RESOLVED',priority='HIGH' WHERE id='"
            + row.id()
            + "'");
    var exact = new Filter("alpha", "TASK", "RESOLVED", ACTOR, ACTOR, "HIGH");
    assertEquals(1, search(exact, 0, 10).total());
    assertEquals(0, search(new Filter("unknown", null, null, null, null, null), 0, 10).total());
    assertEquals(0, search(new Filter(null, "CUSTOM", null, null, null, null), 0, 10).total());
    assertEquals(0, search(new Filter(null, null, "MISSING", null, null, null), 0, 10).total());
    assertEquals(
        0, search(new Filter(null, null, null, UUID.randomUUID(), null, null), 0, 10).total());
    assertEquals(
        0, search(new Filter(null, null, null, null, UUID.randomUUID(), null), 0, 10).total());
  }

  @Test
  void textIsLiteralAndCannotInjectSqlOrBroadenWildcards() {
    seed(A, PROJECT, "literal 10%_! value");
    seed(A, PROJECT, "ordinary");
    assertEquals(1, search(new Filter("%_!", null, null, null, null, null), 0, 10).total());
    assertEquals(
        0, search(new Filter("' OR true --", null, null, null, null, null), 0, 10).total());
    assertEquals(2, search(new Filter("  ", null, null, null, null, null), 0, 10).total());
  }

  @Test
  void deletedRowsNeverAffectSearchBoardOrCounts() {
    var row = seed(A, PROJECT, "deleted");
    seed(A, PROJECT, "live");
    sql("UPDATE taska.issues SET deleted_at=now() WHERE id='" + row.id() + "'");
    assertEquals(1, search(Filter.all(), 0, 10).total());
    assertEquals(1, board(null, true, 10, null, null).total());
  }

  @Test
  void doneUsesWorkflowCategoryRatherThanLiteralStatusName() {
    var a = seed(A, PROJECT, "done by category");
    var b = seed(A, PROJECT, "not done by category");
    sql("UPDATE taska.issues SET status_key='RESOLVED' WHERE id='" + a.id() + "'");
    sql("UPDATE taska.issues SET status_key='DONE' WHERE id='" + b.id() + "'");
    var page = board(null, false, 10, null, null);
    assertEquals(1, page.total());
    assertEquals(b.id(), page.issues().getFirst().issue().identity().id());
    done = Set.of("DONE");
    var changed = board(null, false, 10, null, null);
    assertEquals(a.id(), changed.issues().getFirst().issue().identity().id());
  }

  @Test
  void perColumnLimitAndWindowTotalsExcludeHiddenScopes() {
    for (int i = 0; i < 4; i++) seed(A, PROJECT, "todo " + i);
    var d = seed(A, PROJECT, "resolved");
    sql("UPDATE taska.issues SET status_key='RESOLVED' WHERE id='" + d.id() + "'");
    for (int i = 0; i < 5; i++) {
      seed(B, PROJECT, "B");
      seed(A, hidden, "hidden");
    }
    var page = board(null, true, 2, null, null);
    assertEquals(5, page.total());
    assertEquals(3, page.issues().size());
    for (var card : page.issues()) {
      boolean todo = card.issue().statusKey().equals("TODO");
      assertEquals(todo ? 4 : 1, card.statusTotal());
      assertEquals(todo, card.hasMore());
    }
  }

  @Test
  void ascendingCursorUsesIdTieBreakAndKeepsOriginalCountOnEmptyPage() {
    seed(A, PROJECT, "a");
    seed(A, PROJECT, "b");
    seed(A, PROJECT, "c");
    sql("UPDATE taska.issues SET created_at='2026-01-01T00:00:00Z'");
    var first = board("TODO", true, 2, null, null);
    assertEquals(3, first.total());
    assertTrue(first.issues().getFirst().hasMore());
    var last = first.issues().getLast().issue();
    var next =
        board(
            "TODO",
            true,
            2,
            null,
            new TenantIssueQueries.Cursor(last.createdAt().toInstant(), last.identity().id()));
    assertEquals(1, next.issues().size());
    assertEquals(3, next.total());
    assertEquals(3, next.issues().getFirst().statusTotal());
    assertFalse(next.issues().getFirst().hasMore());
    last = next.issues().getLast().issue();
    var empty =
        board(
            "TODO",
            true,
            2,
            null,
            new TenantIssueQueries.Cursor(last.createdAt().toInstant(), last.identity().id()));
    assertTrue(empty.issues().isEmpty());
    assertEquals(3, empty.total());
    assertTrue(board("MISSING", true, 2, null, null).issues().isEmpty());
  }

  @Test
  void doneCursorSortsByUpdatedDescendingAndAppliesDoneSinceOnlyToDone() {
    var a = seed(A, PROJECT, "old");
    var b = seed(A, PROJECT, "new");
    seed(A, PROJECT, "todo");
    sql("UPDATE taska.issues SET updated_at='2026-01-01T00:00:00Z'");
    sql(
        "UPDATE taska.issues SET status_key='RESOLVED' WHERE id IN ('"
            + a.id()
            + "','"
            + b.id()
            + "')");
    sql("UPDATE taska.issues SET updated_at='2026-02-01T00:00:00Z' WHERE id='" + b.id() + "'");
    var first = board("RESOLVED", true, 1, null, null);
    assertEquals(b.id(), first.issues().getFirst().issue().identity().id());
    var next =
        board(
            "RESOLVED",
            true,
            1,
            null,
            new TenantIssueQueries.Cursor(Instant.parse("2026-02-01T00:00:00Z"), b.id()));
    assertEquals(a.id(), next.issues().getFirst().issue().identity().id());
    assertEquals(2, next.total());
    var recent = board(null, true, 10, Instant.parse("2026-01-15T00:00:00Z"), null);
    assertEquals(2, recent.total());
    assertFalse(recent.issues().stream().anyMatch(c -> c.issue().identity().id().equals(a.id())));
  }

  @Test
  void currentAclIsReadAgainAndMixedBatchCannotRevealPartialCounts() {
    seed(A, PROJECT, "visible");
    assertEquals(1, search(Filter.all(), 0, 10).total());
    allowed = false;
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        denied(queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)), "searchIssues")
            .getStatus());
    allowed = true;
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        denied(
                queries.search(new Search(List.of(PROJECT, hidden), Filter.all(), 0, 10)),
                "searchIssues")
            .getStatus());
  }

  @Test
  void malformedOrExpiredPermitsAreRejectedBeforeReturningData() {
    seed(A, PROJECT, "visible");
    for (int kind = 0; kind < 6; kind++) {
      final int k = kind;
      when(f.access.readMany(anyList()))
          .thenAnswer(
              call ->
                  VerifiedTenantContext.current()
                      .map(
                          c -> {
                            var p =
                                new TenantIssueAccess.Permit(
                                    k == 0 ? B : A,
                                    k == 1 ? UUID.randomUUID() : ACTOR,
                                    k == 2 ? "anotherAction" : c.action(),
                                    k == 3 ? hidden : PROJECT,
                                    k == 4 ? Instant.EPOCH : Instant.now().plusSeconds(30),
                                    false,
                                    false,
                                    Set.of());
                            return new TenantIssueAccess.ReadBatch(
                                k == 5 ? List.of(p, p) : List.of(p), 50L, false);
                          }));
      var e =
          denied(queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)), "searchIssues");
      assertTrue(
          Set.of(DomainStatus.PERMISSION_DENIED, DomainStatus.UNAVAILABLE).contains(e.getStatus()));
    }
  }

  @Test
  void emptyOrUnavailableAuthorityAndWorkflowNeverFallBackToGlobalRead() {
    when(f.access.readMany(anyList())).thenReturn(Mono.empty());
    assertEquals(
        DomainStatus.UNAVAILABLE,
        denied(queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)), "searchIssues")
            .getStatus());
    f.businessBoundary();
    queries = new TenantIssueQueries(TenantIssueStorageIntegrationTest.store, f.access);
    when(f.access.boardWorkflow(PROJECT, "TASK")).thenReturn(Mono.empty());
    assertEquals(
        DomainStatus.UNAVAILABLE,
        denied(
                queries.board(
                    new Board(
                        PROJECT,
                        new Filter(null, "TASK", null, null, null, null),
                        true,
                        10,
                        null,
                        null)),
                "listIssuesForBoard")
            .getStatus());
  }

  @Test
  void administrativeAuditIsAtomicAndReadDoesNotMutateBusinessState() {
    seed(A, PROJECT, "visible");
    long history = f.count("issue_history"), outbox = f.count("outbox_events");
    audit = true;
    assertEquals(1, search(Filter.all(), 0, 10).total());
    assertEquals(1, f.count("issue_authorization_audit"));
    sql(
        "ALTER TABLE taska.issue_authorization_audit ADD CONSTRAINT deny_query_audit"
            + " CHECK(action<>'searchIssues') NOT VALID");
    try {
      assertThrows(RuntimeException.class, () -> search(Filter.all(), 0, 10));
    } finally {
      sql("ALTER TABLE taska.issue_authorization_audit DROP CONSTRAINT deny_query_audit");
    }
    assertEquals(1, f.count("issue_authorization_audit"));
    assertEquals(history, f.count("issue_history"));
    assertEquals(outbox, f.count("outbox_events"));
    assertEquals(1, f.count("idempotency_keys"));
  }

  @Test
  void unverifiedCallsAndInvalidQueryShapesAreRejected() {
    assertThrows(
        DomainException.class,
        () -> queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)).block(WAIT));
    assertThrows(DomainException.class, () -> new Search(List.of(), Filter.all(), 0, 10));
    assertThrows(
        DomainException.class, () -> new Search(List.of(PROJECT, PROJECT), Filter.all(), 0, 10));
    assertThrows(
        DomainException.class,
        () -> new Search(List.of(PROJECT), Filter.all(), Integer.MAX_VALUE, 101));
    assertThrows(
        DomainException.class, () -> new Filter(null, "TASK;DROP", null, null, null, null));
    assertThrows(
        DomainException.class,
        () ->
            new Board(
                PROJECT,
                new Filter(null, "TASK", null, null, null, null),
                true,
                10,
                null,
                new TenantIssueQueries.Cursor(Instant.now(), UUID.randomUUID())));
    assertThrows(
        DomainException.class,
        () -> new TenantIssueQueries.Workflow(Set.of("TODO"), Set.of("FOREIGN")));
  }

  @Test
  void batchedCountsRejectAuthorityScopeExpansion() {
    when(f.access.readMany(anyList()))
        .thenAnswer(
            call ->
                VerifiedTenantContext.current()
                    .map(
                        c ->
                            new TenantIssueAccess.ReadBatch(
                                List.of(permit(c, PROJECT), permit(c, hidden)), 50L, false)));
    var q =
        ru.taska.api.issue.v1.CountProjectIssuesBatchInternalRequest.newBuilder()
            .addProjectIds(PROJECT.toString())
            .build();
    assertEquals(
        DomainStatus.UNAVAILABLE,
        denied(f.api.counts(q), "countProjectIssuesBatchInternal").getStatus());
  }

  @Test
  void permitExpiryDuringSqlAuditRollsBackAuditAndSuppressesResult() {
    seed(A, PROJECT, "visible");
    audit = true;
    sql(
        "CREATE FUNCTION taska.slow_query_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " PERFORM pg_sleep(1); RETURN NEW; END $$; CREATE TRIGGER slow_query_audit BEFORE"
            + " INSERT ON taska.issue_authorization_audit FOR EACH ROW EXECUTE FUNCTION"
            + " taska.slow_query_audit()");
    f.expiry = Instant.now().plusMillis(600);
    try {
      assertEquals(
          DomainStatus.UNAVAILABLE,
          denied(queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)), "searchIssues")
              .getStatus());
    } finally {
      sql(
          "DROP TRIGGER slow_query_audit ON taska.issue_authorization_audit; DROP FUNCTION"
              + " taska.slow_query_audit()");
    }
    assertEquals(0, f.count("issue_authorization_audit"));
  }

  @Test
  void unrelatedActionCannotReuseReadPermitAndLargeOffsetDoesNotOverflow() {
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        denied(queries.search(new Search(List.of(PROJECT), Filter.all(), 0, 10)), "getIssue")
            .getStatus());
    seed(A, PROJECT, "visible");
    var p = search(Filter.all(), Integer.MAX_VALUE, 100);
    assertEquals(1, p.total());
    assertTrue(p.issues().isEmpty());
  }

  @Test
  void scopedDoneCursorIndexIsUsableWithTwoTenantStatistics() {
    seed(A, PROJECT, "visible");
    seed(B, hidden, "foreign");
    sql(
        "INSERT INTO"
            + " taska.issues(tenant_id,project_id,issue_number,issue_key,issue_type,summary,status_key,priority,reporter_id)"
            + " SELECT '"
            + B
            + "','"
            + hidden
            + "',n+100,'PERF-'||n,'TASK','foreign','RESOLVED','MEDIUM','"
            + ACTOR
            + "' FROM generate_series(1,12000) n; ANALYZE taska.issues");
    var plan =
        as(
                A,
                ACTOR,
                "listIssuesForBoard",
                TenantIssueStorageIntegrationTest.tx.execute(
                    db ->
                        db.sql(
                                "EXPLAIN (COSTS OFF) SELECT * FROM taska.issues WHERE tenant_id=:t"
                                    + " AND project_id=:p AND issue_type='TASK' AND"
                                    + " status_key='RESOLVED' AND deleted_at IS NULL ORDER BY"
                                    + " updated_at DESC,id DESC LIMIT 10")
                            .bind("t", A)
                            .bind("p", PROJECT)
                            .map((r, m) -> r.get(0, String.class))
                            .all()
                            .collectList()))
            .block(WAIT);
    assertTrue(
        String.join("\n", plan).contains("issues_board_updated_cursor_active_idx"),
        plan.toString());
    assertEquals(1, search(Filter.all(), 0, 10).total());
  }

  @Test
  void searchAndBoardRpcRemainClosedUntilDirectoryAdapterIsReady() {
    var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
    beans.registerSingleton(
        "trust", org.mockito.Mockito.mock(ru.taska.security.tenant.RpcContextSecurity.class));
    var interceptor =
        new ru.taska.issuebootstrap.TenantStorageConfiguration()
            .issueTenantTransportClosed(
                beans.getBeanProvider(ru.taska.security.tenant.RpcContextSecurity.class), "test");
    for (var descriptor :
        List.of(
            ru.taska.api.issue.v1.IssueServiceGrpc.getSearchIssuesMethod(),
            ru.taska.api.issue.v1.IssueServiceGrpc.getListIssuesForBoardMethod())) {
      @SuppressWarnings("unchecked")
      io.grpc.ServerCall<Object, Object> call = org.mockito.Mockito.mock(io.grpc.ServerCall.class);
      org.mockito.Mockito.doReturn(descriptor).when(call).getMethodDescriptor();
      interceptor.interceptCall(
          call,
          new io.grpc.Metadata(),
          (c, h) -> {
            throw new AssertionError("Blocked RPC must not reach handler");
          });
      org.mockito.Mockito.verify(call)
          .close(
              org.mockito.ArgumentMatchers.argThat(
                  status -> status.getCode() == io.grpc.Status.Code.UNAVAILABLE),
              org.mockito.ArgumentMatchers.any());
    }
  }
}

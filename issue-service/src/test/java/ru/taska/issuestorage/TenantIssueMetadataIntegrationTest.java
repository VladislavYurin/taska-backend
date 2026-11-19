package ru.taska.issuestorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.taska.security.tenant.IssueStorageTestContext.as;
import static ru.taska.security.tenant.RecipientTestContext.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.IssueRecipientsRequest;
import ru.taska.api.issue.v1.AddIssueLabelRequestBody;
import ru.taska.api.issue.v1.CreateProjectLabelRequestBody;
import ru.taska.api.issue.v1.DeleteProjectLabelRequestBody;
import ru.taska.api.issue.v1.GetIssueWatchStateRequestBody;
import ru.taska.api.issue.v1.ListIssueLabelsRequestBody;
import ru.taska.api.issue.v1.ListIssueWatchersRequestBody;
import ru.taska.api.issue.v1.ListProjectLabelsRequestBody;
import ru.taska.api.issue.v1.RemoveIssueLabelRequestBody;
import ru.taska.api.issue.v1.UnwatchIssueRequestBody;
import ru.taska.api.issue.v1.UpdateProjectLabelRequestBody;
import ru.taska.api.issue.v1.WatchIssueRequestBody;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Настоящие app LOGIN/RLS/FK и транзакции; primary ports управляемые, production wiring проверяет
 * E2E.
 */
@Testcontainers
@Timeout(120)
class TenantIssueMetadataIntegrationTest {
  @Container static final PostgreSQLContainer<?> pg = TenantIssueStorageIntegrationTest.pg;
  static final UUID A = TenantIssueStorageIntegrationTest.A,
      B = TenantIssueStorageIntegrationTest.B,
      ACTOR = TenantIssueStorageIntegrationTest.ACTOR,
      PROJECT = TenantIssueStorageIntegrationTest.PROJECT;
  static final Duration WAIT = Duration.ofSeconds(30);
  static final TenantIssueStorageIntegrationTest fixtures = new TenantIssueStorageIntegrationTest();
  static TenantIssueMetadataStore store;
  TenantIssueMetadataApi api;
  UUID issue;
  final Set<UUID> eligible = new HashSet<>();
  final AtomicBoolean denied = new AtomicBoolean(), offline = new AtomicBoolean();

  @BeforeAll
  static void setup() throws Exception {
    TenantIssueStorageIntegrationTest.setup();
    store =
        new TenantIssueMetadataStore(
            TenantIssueStorageIntegrationTest.tx,
            TenantIssueStorageIntegrationTest.json,
            Clock.systemUTC());
  }

  @AfterAll
  static void close() {
    TenantIssueStorageIntegrationTest.close();
  }

  @BeforeEach
  void reset() {
    fixtures.reset();
    TenantIssueStorageIntegrationTest.sql("TRUNCATE taska.project_labels CASCADE");
    issue = fixtures.create(A, "metadata-issue").id();
    eligible.clear();
    eligible.add(ACTOR);
    denied.set(false);
    offline.set(false);
    var access = Mockito.mock(TenantIssueAccess.class);
    Mockito.when(
            access.authorize(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.nullable(UUID.class)))
        .thenAnswer(
            call ->
                VerifiedTenantContext.current()
                    .flatMap(
                        c ->
                            denied.get()
                                ? Mono.error(
                                    new DomainException(
                                        DomainStatus.PERMISSION_DENIED, "CURRENT_POLICY_DENIED"))
                                : Mono.just(
                                    new TenantIssueAccess.Permit(
                                        UUID.fromString(c.tenantId()),
                                        UUID.fromString(c.actorId()),
                                        c.action(),
                                        call.getArgument(1, UUID.class),
                                        Instant.now().plusSeconds(30),
                                        false,
                                        false,
                                        Set.of()))));
    api =
        new TenantIssueMetadataApi(
            store,
            access,
            (project, ids, read) ->
                offline.get()
                    ? Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "PRIMARY_OFFLINE"))
                    : Mono.just(
                        ids.stream()
                            .filter(eligible::contains)
                            .collect(java.util.stream.Collectors.toSet())),
            20,
            100);
  }

  <T> T run(String action, Mono<T> work) {
    return as(A, ACTOR, action, work).block(WAIT);
  }

  TenantIssueAccess.Permit permit(UUID tenant, UUID project, String action) {
    return new TenantIssueAccess.Permit(
        tenant, ACTOR, action, project, Instant.now().plusSeconds(30), false, false, Set.of());
  }

  UUID label(String name) {
    return UUID.fromString(
        run(
                "createProjectLabel",
                api.create(
                    CreateProjectLabelRequestBody.newBuilder()
                        .setProjectId(PROJECT.toString())
                        .setCreatedBy(ACTOR.toString())
                        .setName(name)
                        .setColor("#112233")
                        .build()))
            .getId());
  }

  AddIssueLabelRequestBody attach(UUID label) {
    return AddIssueLabelRequestBody.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(issue.toString())
        .setLabelId(label.toString())
        .build();
  }

  WatchIssueRequestBody watch(UUID user) {
    return WatchIssueRequestBody.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(issue.toString())
        .setTargetUserId(user.toString())
        .build();
  }

  ListIssueWatchersRequestBody watchers() {
    return ListIssueWatchersRequestBody.newBuilder()
        .setActorUserId(ACTOR.toString())
        .setProjectId(PROJECT.toString())
        .setIssueId(issue.toString())
        .build();
  }

  long count(String table) {
    return fixtures.count(table);
  }

  long version() {
    return run("getIssue", store.issue(issue)).version();
  }

  DomainException fail(Mono<?> work) {
    return assertThrows(DomainException.class, () -> run("watchIssue", work));
  }

  @Test
  void identicalNamesAreIndependentButForeignAndOtherProjectLabelsCannotAttach() {
    UUID own = label("backend");
    UUID foreign =
        as(
                B,
                ACTOR,
                "createProjectLabel",
                store.createLabel(permit(B, PROJECT, "createProjectLabel"), "backend", "#112233"))
            .block(WAIT)
            .id();
    assertNotEquals(own, foreign);
    UUID otherProject = UUID.randomUUID();
    UUID other =
        run(
                "createProjectLabel",
                store.createLabel(
                    permit(A, otherProject, "createProjectLabel"), "backend", "#112233"))
            .id();
    for (UUID id : List.of(foreign, other))
      assertEquals(DomainStatus.NOT_FOUND, fail(api.attach(attach(id))).getStatus());
    assertEquals(0, count("issue_labels"));
    assertEquals(1, version());
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void appRoleEnforcesSameTenantProjectForeignKeysAndRls() {
    UUID otherProject = UUID.randomUUID();
    UUID other =
        run(
                "createProjectLabel",
                store.createLabel(
                    permit(A, otherProject, "createProjectLabel"), "other", "#112233"))
            .id();
    var raw = TenantIssueStorageIntegrationTest.raw;
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                as(
                        A,
                        ACTOR,
                        TenantIssueStorageIntegrationTest.tx.execute(
                            db ->
                                db.sql(
                                        "INSERT INTO"
                                            + " taska.issue_labels(tenant_id,project_id,issue_id,label_id,created_by)"
                                            + " VALUES(:t,:p,:i,:l,:a)")
                                    .bind("t", A)
                                    .bind("p", PROJECT)
                                    .bind("i", issue)
                                    .bind("l", other)
                                    .bind("a", ACTOR)
                                    .fetch()
                                    .rowsUpdated()))
                    .block(WAIT));
    assertEquals("23503", TenantIssueStorageIntegrationTest.state(failure));
    var p = permit(A, PROJECT, "watchIssue");
    run("watchIssue", store.watch(p, issue, ACTOR));
    assertThrows(
        DomainException.class, () -> as(B, ACTOR, "getIssue", store.issue(issue)).block(WAIT));
    assertThrows(
        RuntimeException.class, () -> raw.sql("TRUNCATE taska.issue_watchers").then().block(WAIT));
    assertEquals(0, count("issue_labels"));
    assertEquals(1, count("issue_watchers"));
  }

  @Test
  void parallelAttachAndRepeatedDetachHaveOneEffect() {
    UUID label = label("one");
    var results =
        run(
            "addIssueLabel",
            Flux.range(0, 6).flatMap(i -> api.attach(attach(label)), 6).collectList());
    assertEquals(1, results.stream().distinct().count());
    assertEquals(1, count("issue_labels"));
    assertEquals(2, version());
    assertEquals(2, count("issue_history"));
    assertEquals(2, count("outbox_events"));
    var q =
        RemoveIssueLabelRequestBody.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setProjectId(PROJECT.toString())
            .setIssueId(issue.toString())
            .setLabelId(label.toString())
            .build();
    run("removeIssueLabel", api.detach(q));
    run("removeIssueLabel", api.detach(q));
    assertEquals(0, count("issue_labels"));
    assertEquals(3, version());
    assertEquals(3, count("outbox_events"));
  }

  @Test
  void softDeleteIsRepeatableAndDoesNotResurrectAssignments() {
    UUID old = label("same");
    run("addIssueLabel", api.attach(attach(old)));
    var q =
        DeleteProjectLabelRequestBody.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setProjectId(PROJECT.toString())
            .setLabelId(old.toString())
            .build();
    assertEquals(
        run("deleteProjectLabel", api.delete(q)), run("deleteProjectLabel", api.delete(q)));
    UUID next = label("same");
    assertNotEquals(old, next);
    var labels =
        run(
            "listIssueLabels",
            api.issueLabels(
                ListIssueLabelsRequestBody.newBuilder()
                    .setActorUserId(ACTOR.toString())
                    .setProjectId(PROJECT.toString())
                    .setIssueId(issue.toString())
                    .build()));
    assertEquals(0, labels.getLabelsCount());
    assertEquals(DomainStatus.NOT_FOUND, fail(api.attach(attach(old))).getStatus());
    assertEquals(1, count("issue_labels"));
    assertEquals(2, version());
  }

  @Test
  void duplicateNameAndFailedUpdatePreserveOriginalLabel() {
    UUID one = label("one"), two = label("two");
    var q =
        UpdateProjectLabelRequestBody.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setProjectId(PROJECT.toString())
            .setLabelId(two.toString())
            .setName("ONE")
            .setColor("#445566")
            .build();
    assertEquals(DomainStatus.ALREADY_EXISTS, fail(api.update(q)).getStatus());
    var rows =
        run(
            "listProjectLabels",
            api.projectLabels(
                ListProjectLabelsRequestBody.newBuilder()
                    .setActorUserId(ACTOR.toString())
                    .setProjectId(PROJECT.toString())
                    .build()));
    assertEquals(
        List.of("one", "two"), rows.getLabelsList().stream().map(x -> x.getName()).toList());
    assertEquals(2, count("project_labels"));
  }

  @Test
  void concurrentWatchAndUnwatchHaveOneEffectAndNoProfiles() {
    var rows =
        run("watchIssue", Flux.range(0, 6).flatMap(i -> api.watch(watch(ACTOR)), 6).collectList());
    assertEquals(1, rows.stream().map(x -> x.getWatcher().getId()).distinct().count());
    assertEquals(1, count("issue_watchers"));
    assertEquals(2, version());
    assertFalse(rows.getFirst().getWatcher().hasDisplayName());
    assertFalse(rows.getFirst().getWatcher().hasAvatarUrl());
    var q =
        UnwatchIssueRequestBody.newBuilder()
            .setActorUserId(ACTOR.toString())
            .setProjectId(PROJECT.toString())
            .setIssueId(issue.toString())
            .build();
    assertTrue(run("unwatchIssue", api.unwatch(q)).getRemoved());
    assertFalse(run("unwatchIssue", api.unwatch(q)).getRemoved());
    assertEquals(3, version());
    assertEquals(3, count("outbox_events"));
  }

  @Test
  void revokedTargetAndOfflinePrimaryDoNotCreateWatcher() {
    UUID target = UUID.randomUUID();
    assertEquals(DomainStatus.INVALID_ARGUMENT, fail(api.watch(watch(target))).getStatus());
    offline.set(true);
    assertEquals(DomainStatus.UNAVAILABLE, fail(api.watch(watch(ACTOR))).getStatus());
    assertEquals(0, count("issue_watchers"));
    assertEquals(1, version());
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void revokedActorWrongProjectAndForgedActorPreserveState() {
    UUID label = label("owned");
    denied.set(true);
    assertEquals(DomainStatus.PERMISSION_DENIED, fail(api.attach(attach(label))).getStatus());
    denied.set(false);
    assertEquals(
        DomainStatus.NOT_FOUND,
        fail(api.watch(watch(ACTOR).toBuilder().setProjectId(UUID.randomUUID().toString()).build()))
            .getStatus());
    assertEquals(
        DomainStatus.PERMISSION_DENIED,
        fail(api.watch(
                watch(ACTOR).toBuilder().setActorUserId(UUID.randomUUID().toString()).build()))
            .getStatus());
    assertEquals(0, count("issue_labels"));
    assertEquals(0, count("issue_watchers"));
    assertEquals(1, version());
  }

  @Test
  void inactiveWatchersAreFilteredBeforeCountAndPage() {
    UUID first = UUID.randomUUID(), second = UUID.randomUUID(), last = UUID.randomUUID();
    eligible.addAll(List.of(first, second, last));
    for (UUID user : List.of(first, second, last)) run("watchIssue", api.watch(watch(user)));
    eligible.remove(last);
    var page =
        run("listIssueWatchers", api.watchers(watchers().toBuilder().setPageSize(1).build()));
    assertEquals(2, page.getTotalCount());
    assertEquals(second.toString(), page.getWatchers(0).getUserId());
    var next =
        run(
            "listIssueWatchers",
            api.watchers(watchers().toBuilder().setPageSize(1).setPage(1).build()));
    assertEquals(first.toString(), next.getWatchers(0).getUserId());
    offline.set(true);
    assertEquals(DomainStatus.UNAVAILABLE, fail(api.watchers(watchers())).getStatus());
  }

  @Test
  void faultAfterMutationRollsBackWatcherVersionHistoryAndOutbox() {
    TenantIssueStorageIntegrationTest.sql(
        "CREATE FUNCTION taska.reject_metadata_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF NEW.event_type='WATCHED' THEN RAISE EXCEPTION 'injected event failure'; END IF;"
            + " RETURN NEW; END $$");
    TenantIssueStorageIntegrationTest.sql(
        "CREATE TRIGGER metadata_failure BEFORE INSERT ON taska.outbox_events FOR EACH ROW EXECUTE"
            + " FUNCTION taska.reject_metadata_event()");
    try {
      assertThrows(RuntimeException.class, () -> run("watchIssue", api.watch(watch(ACTOR))));
    } finally {
      TenantIssueStorageIntegrationTest.sql("DROP TRIGGER metadata_failure ON taska.outbox_events");
      TenantIssueStorageIntegrationTest.sql("DROP FUNCTION taska.reject_metadata_event()");
    }
    assertEquals(0, count("issue_watchers"));
    assertEquals(1, version());
    assertEquals(1, count("issue_history"));
    assertEquals(1, count("outbox_events"));
  }

  @Test
  void eventsAndHistoryContainNoContentOrProfileSnapshot() {
    UUID label = label("private-label-name");
    run("addIssueLabel", api.attach(attach(label)));
    run("watchIssue", api.watch(watch(ACTOR)));
    String payload =
        TenantIssueStorageIntegrationTest.operator
            .sql("SELECT string_agg(payload::text,' ') p FROM taska.outbox_events")
            .map((r, m) -> r.get("p", String.class))
            .one()
            .block(WAIT);
    for (String forbidden :
        List.of(
            "private-label-name",
            "private description",
            "Private summary",
            "email",
            "displayName",
            "watcherIds",
            "recipientIds")) assertFalse(payload.contains(forbidden), forbidden);
    assertTrue(payload.contains("projectId"));
    assertTrue(payload.contains("labelId"));
    assertTrue(payload.contains("watcherUserId"));
    assertEquals(3, count("issue_history"));
  }

  @Test
  void internalRecipientsNeedExactAuthorityAndKeepCursorAcrossFilteredPage() {
    UUID zero = new UUID(0, 0);
    eligible.add(zero);
    run("watchIssue", api.watch(watch(zero)));
    run("watchIssue", api.watch(watch(ACTOR)));
    eligible.remove(zero);
    var q = IssueRecipientsRequest.newBuilder().setIssueId(issue.toString()).setLimit(1).build();
    var first =
        service(A, "notification-service", "NOTIFICATION_DELIVERY", api.recipients(q)).block(WAIT);
    assertEquals(0, first.getUserIdsCount());
    assertEquals(zero.toString(), first.getNextCursor());
    var second =
        service(
                A,
                "notification-service",
                "NOTIFICATION_DELIVERY",
                api.recipients(q.toBuilder().setCursor(first.getNextCursor()).build()))
            .block(WAIT);
    assertEquals(List.of(ACTOR.toString()), second.getUserIdsList());
    assertEquals(DomainStatus.PERMISSION_DENIED, fail(api.recipients(q)).getStatus());
    for (String issuer : List.of("issue-service", "other-service"))
      assertThrows(
          DomainException.class,
          () -> service(A, issuer, "NOTIFICATION_DELIVERY", api.recipients(q)).block(WAIT));
    assertThrows(
        DomainException.class,
        () -> service(A, "notification-service", "STORAGE_CLEANUP", api.recipients(q)).block(WAIT));
    assertThrows(
        DomainException.class,
        () ->
            service(B, "notification-service", "NOTIFICATION_DELIVERY", api.recipients(q))
                .block(WAIT));
    assertThrows(
        DomainException.class,
        () ->
            service(
                    A,
                    "notification-service",
                    "NOTIFICATION_DELIVERY",
                    api.recipients(q.toBuilder().setLimit(101).build()))
                .block(WAIT));
  }

  @Test
  void watchStateSeesSelfBeyondFirstPageAndNeverExposesRemovedProfile() {
    run("watchIssue", api.watch(watch(ACTOR)));
    for (int i = 0; i < 21; i++) {
      UUID user = UUID.randomUUID();
      eligible.add(user);
      run("watchIssue", api.watch(watch(user)));
    }
    var page = run("listIssueWatchers", api.watchers(watchers()));
    assertEquals(20, page.getWatchersCount());
    assertEquals(22, page.getTotalCount());
    var state =
        run(
            "getIssueWatchState",
            api.state(
                GetIssueWatchStateRequestBody.newBuilder()
                    .setActorUserId(ACTOR.toString())
                    .setProjectId(PROJECT.toString())
                    .setIssueId(issue.toString())
                    .build()));
    assertTrue(state.getWatchedByMe());
    assertEquals(22, state.getWatchersCount());
    eligible.remove(ACTOR);
    assertEquals(21, run("listIssueWatchers", api.watchers(watchers())).getTotalCount());
  }
}

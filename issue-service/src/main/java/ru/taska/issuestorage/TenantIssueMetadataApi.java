package ru.taska.issuestorage;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.CredentialKind;
import ru.taska.api.b2b.v1.IssueRecipientsRequest;
import ru.taska.api.b2b.v1.IssueRecipientsResponse;
import ru.taska.api.issue.v1.AddIssueLabelRequestBody;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.CreateProjectLabelRequestBody;
import ru.taska.api.issue.v1.DeleteProjectLabelRequestBody;
import ru.taska.api.issue.v1.DeleteProjectLabelResponse;
import ru.taska.api.issue.v1.GetIssueWatchStateRequestBody;
import ru.taska.api.issue.v1.GetIssueWatchStateResponse;
import ru.taska.api.issue.v1.IssueWatcherResponse;
import ru.taska.api.issue.v1.ListIssueLabelsRequestBody;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListIssueWatchersRequestBody;
import ru.taska.api.issue.v1.ListIssueWatchersResponse;
import ru.taska.api.issue.v1.ListProjectLabelsRequestBody;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.RemoveIssueLabelRequestBody;
import ru.taska.api.issue.v1.RemoveIssueLabelResponse;
import ru.taska.api.issue.v1.UnwatchIssueRequestBody;
import ru.taska.api.issue.v1.UnwatchIssueResponse;
import ru.taska.api.issue.v1.UpdateProjectLabelRequestBody;
import ru.taska.api.issue.v1.WatchIssueRequestBody;
import ru.taska.api.issue.v1.WatchIssueResponse;
import ru.taska.capability.Action;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.security.tenant.RpcContextBridge;
import ru.taska.security.tenant.VerifiedTenantContext;

/** Публичные операции всегда сохраняют исходного actor; технический resolver не изменяет данные. */
public final class TenantIssueMetadataApi {
  @FunctionalInterface
  public interface Eligibility {
    Mono<Set<UUID>> check(UUID project, List<UUID> users, boolean contentRead);
  }

  private final TenantIssueMetadataStore store;
  private final TenantIssueAccess access;
  private final Eligibility eligibility;
  private final int defaultPage, maxPage;

  public TenantIssueMetadataApi(
      TenantIssueMetadataStore store,
      TenantIssueAccess access,
      Eligibility eligibility,
      int defaultPage,
      int maxPage) {
    if (defaultPage < 1 || maxPage < defaultPage)
      throw new IllegalArgumentException("Invalid watcher pagination");
    this.store = store;
    this.access = access;
    this.eligibility = eligibility;
    this.defaultPage = defaultPage;
    this.maxPage = maxPage;
  }

  public Mono<ProjectLabelResponse> create(CreateProjectLabelRequestBody q) {
    return actor(q.getCreatedBy())
        .then(
            Mono.defer(
                () -> {
                  validate(q.getName(), q.getColor());
                  return access
                      .authorize(Action.LABEL_CONFIGURE, id(q.getProjectId()), null)
                      .flatMap(p -> store.createLabel(p, q.getName(), q.getColor()))
                      .map(TenantIssueMetadataApi::label)
                      .onErrorMap(
                          org.springframework.dao.DuplicateKeyException.class,
                          e ->
                              new DomainException(
                                  DomainStatus.ALREADY_EXISTS, "LABEL_NAME_EXISTS"));
                }));
  }

  public Mono<ProjectLabelResponse> update(UpdateProjectLabelRequestBody q) {
    return actor(q.getActorUserId())
        .then(
            Mono.defer(
                () -> {
                  validate(q.getName(), q.getColor());
                  return access
                      .authorize(Action.LABEL_CONFIGURE, id(q.getProjectId()), null)
                      .flatMap(
                          p -> store.updateLabel(p, id(q.getLabelId()), q.getName(), q.getColor()))
                      .map(TenantIssueMetadataApi::label)
                      .onErrorMap(
                          org.springframework.dao.DuplicateKeyException.class,
                          e ->
                              new DomainException(
                                  DomainStatus.ALREADY_EXISTS, "LABEL_NAME_EXISTS"));
                }));
  }

  public Mono<DeleteProjectLabelResponse> delete(DeleteProjectLabelRequestBody q) {
    return actor(q.getActorUserId())
        .then(access.authorize(Action.LABEL_CONFIGURE, id(q.getProjectId()), null))
        .flatMap(p -> store.deleteLabel(p, id(q.getLabelId())))
        .map(
            l ->
                DeleteProjectLabelResponse.newBuilder()
                    .setLabelId(l.id().toString())
                    .setProjectId(l.project().toString())
                    .setDeletedAt(TenantIssueApi.timestamp(l.deleted()))
                    .build());
  }

  public Mono<ListProjectLabelsResponse> projectLabels(ListProjectLabelsRequestBody q) {
    return actor(q.getActorUserId())
        .then(access.authorize(Action.LABEL_READ, id(q.getProjectId()), null))
        .flatMap(store::projectLabels)
        .map(
            rows ->
                ListProjectLabelsResponse.newBuilder()
                    .addAllLabels(rows.stream().map(TenantIssueMetadataApi::label).toList())
                    .setTotalCount(rows.size())
                    .build());
  }

  public Mono<AddIssueLabelResponse> attach(AddIssueLabelRequestBody q) {
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), Action.LABEL_ATTACH)
        .flatMap(p -> store.attach(p, id(q.getIssueId()), id(q.getLabelId())))
        .map(
            a ->
                AddIssueLabelResponse.newBuilder()
                    .setIssueId(a.issue().toString())
                    .setLabelId(a.label().toString())
                    .setCreatedBy(a.creator().toString())
                    .setCreatedAt(TenantIssueApi.timestamp(a.created()))
                    .build());
  }

  public Mono<RemoveIssueLabelResponse> detach(RemoveIssueLabelRequestBody q) {
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), Action.LABEL_DETACH)
        .flatMap(p -> store.detach(p, id(q.getIssueId()), id(q.getLabelId())))
        .map(
            removed ->
                RemoveIssueLabelResponse.newBuilder()
                    .setIssueId(q.getIssueId())
                    .setLabelId(q.getLabelId())
                    .build());
  }

  public Mono<ListIssueLabelsResponse> issueLabels(ListIssueLabelsRequestBody q) {
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), Action.LABEL_READ)
        .flatMap(p -> store.issueLabels(p, id(q.getIssueId())))
        .map(
            rows ->
                ListIssueLabelsResponse.newBuilder()
                    .addAllLabels(rows.stream().map(TenantIssueMetadataApi::label).toList())
                    .build());
  }

  public Mono<WatchIssueResponse> watch(WatchIssueRequestBody q) {
    UUID target = id(q.hasTargetUserId() ? q.getTargetUserId() : q.getActorUserId());
    Action action =
        target.toString().equals(q.getActorUserId()) ? Action.WATCHER_SELF : Action.WATCHER_MANAGE;
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), action)
        .flatMap(
            p ->
                eligibility
                    .check(p.project(), List.of(target), false)
                    .flatMap(
                        allowed -> {
                          if (!allowed.contains(target))
                            return Mono.error(
                                new DomainException(
                                    DomainStatus.INVALID_ARGUMENT, "WATCHER_NOT_ACTIVE"));
                          return store.watch(p, id(q.getIssueId()), target);
                        })
                    .flatMap(
                        w ->
                            visible(p, id(q.getIssueId()))
                                .map(
                                    rows ->
                                        WatchIssueResponse.newBuilder()
                                            .setWatcher(watcher(w))
                                            .setWatchersCount(rows.size())
                                            .build())));
  }

  public Mono<UnwatchIssueResponse> unwatch(UnwatchIssueRequestBody q) {
    UUID target = id(q.hasTargetUserId() ? q.getTargetUserId() : q.getActorUserId());
    Action action =
        target.toString().equals(q.getActorUserId()) ? Action.WATCHER_SELF : Action.WATCHER_MANAGE;
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), action)
        .flatMap(
            p ->
                store
                    .unwatch(p, id(q.getIssueId()), target)
                    .flatMap(
                        removed ->
                            visible(p, id(q.getIssueId()))
                                .map(
                                    rows ->
                                        UnwatchIssueResponse.newBuilder()
                                            .setIssueId(q.getIssueId())
                                            .setRemoved(removed)
                                            .setWatchersCount(rows.size())
                                            .build())));
  }

  public Mono<ListIssueWatchersResponse> watchers(ListIssueWatchersRequestBody q) {
    int page = q.hasPage() ? q.getPage() : 0,
        size = q.hasPageSize() ? q.getPageSize() : defaultPage;
    if (page < 0 || size < 1 || size > maxPage) return Mono.error(invalid("INVALID_WATCHER_PAGE"));
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), Action.WATCHER_READ)
        .flatMap(p -> visible(p, id(q.getIssueId())))
        .map(
            rows ->
                ListIssueWatchersResponse.newBuilder()
                    .setTotalCount(rows.size())
                    .addAllWatchers(
                        rows.stream()
                            .skip((long) page * size)
                            .limit(size)
                            .map(TenantIssueMetadataApi::watcher)
                            .toList())
                    .build());
  }

  public Mono<GetIssueWatchStateResponse> state(GetIssueWatchStateRequestBody q) {
    return scope(q.getActorUserId(), q.getProjectId(), q.getIssueId(), Action.WATCHER_READ)
        .flatMap(p -> visible(p, id(q.getIssueId())))
        .map(
            rows ->
                GetIssueWatchStateResponse.newBuilder()
                    .setWatchersCount(rows.size())
                    .setWatchedByMe(
                        rows.stream().anyMatch(w -> w.user().toString().equals(q.getActorUserId())))
                    .build());
  }

  public Mono<IssueRecipientsResponse> recipients(IssueRecipientsRequest q) {
    return VerifiedTenantContext.current()
        .flatMap(
            c -> {
              var claim = RpcContextBridge.details(c).getContext();
              if (claim.getCredentialKind() != CredentialKind.CREDENTIAL_KIND_SERVICE
                  || !claim.getIssuerService().equals("notification-service")
                  || !claim.getServiceIdentity().equals("notification-service")
                  || !claim.getServicePurpose().equals("NOTIFICATION_DELIVERY")
                  || !c.scopes().containsAll(Set.of("read", "notifications:recipients")))
                return Mono.error(
                    new DomainException(
                        DomainStatus.PERMISSION_DENIED,
                        "NOTIFICATION_RECIPIENT_AUTHORITY_REQUIRED"));
              int limit = q.getLimit() == 0 ? 100 : q.getLimit();
              if (limit < 1 || limit > 100) return Mono.error(invalid("INVALID_RECIPIENT_PAGE"));
              UUID issue = id(q.getIssueId()),
                  cursor = q.getCursor().isEmpty() ? null : id(q.getCursor());
              return store
                  .issue(issue)
                  .flatMap(
                      i ->
                          store
                              .candidates(issue, cursor, limit)
                              .flatMap(
                                  rows ->
                                      filter(i.project(), rows)
                                          .map(
                                              visible -> {
                                                var result =
                                                    IssueRecipientsResponse.newBuilder()
                                                        .addAllUserIds(
                                                            visible.stream()
                                                                .map(w -> w.user().toString())
                                                                .toList());
                                                if (rows.size() == limit)
                                                  result.setNextCursor(
                                                      rows.getLast().user().toString());
                                                return result.build();
                                              })));
            });
  }

  private record View(
      List<ProjectLabelResponse> labels,
      List<IssueWatcherResponse> watchers,
      int total,
      boolean watching) {}

  private Mono<View> view(String actor, String project, String issue) {
    return Mono.zip(
            scope(actor, project, issue, Action.LABEL_READ),
            scope(actor, project, issue, Action.WATCHER_READ))
        .flatMap(
            p -> Mono.zip(store.issueLabels(p.getT1(), id(issue)), visible(p.getT2(), id(issue))))
        .map(
            rows ->
                new View(
                    rows.getT1().stream().map(TenantIssueMetadataApi::label).toList(),
                    rows.getT2().stream()
                        .limit(defaultPage)
                        .map(TenantIssueMetadataApi::watcher)
                        .toList(),
                    rows.getT2().size(),
                    rows.getT2().stream().anyMatch(w -> w.user().toString().equals(actor))));
  }

  public Mono<ru.taska.api.issue.v1.IssueResponse> enrich(
      ru.taska.api.issue.v1.IssueResponse value, String actor) {
    return view(actor, value.getProjectId(), value.getId())
        .map(
            v ->
                value.toBuilder()
                    .addAllLabels(v.labels())
                    .setWatchersCount(v.total())
                    .setWatchedByMe(v.watching())
                    .build());
  }

  public Mono<ru.taska.api.issue.v1.IssueWithHistoryResponse> enrich(
      ru.taska.api.issue.v1.IssueWithHistoryResponse value, String actor) {
    var issue = value.getIssue();
    return view(actor, issue.getProjectId(), issue.getId())
        .map(
            v ->
                value.toBuilder()
                    .setIssue(
                        issue.toBuilder()
                            .addAllLabels(v.labels())
                            .setWatchersCount(v.total())
                            .setWatchedByMe(v.watching()))
                    .build());
  }

  public Mono<ru.taska.api.issue.v1.GetIssueDetailsResponse> enrich(
      ru.taska.api.issue.v1.GetIssueDetailsResponse value, String actor) {
    var issue = value.getIssue();
    return view(actor, issue.getProjectId(), issue.getId())
        .map(
            v ->
                value.toBuilder()
                    .setIssue(
                        issue.toBuilder()
                            .setLabels(
                                ru.taska.api.issue.v1.ListLabelsResponse.newBuilder()
                                    .addAllLabels(
                                        v.labels().stream()
                                            .map(
                                                l ->
                                                    ru.taska.api.issue.v1.IssueLabelResponse
                                                        .newBuilder()
                                                        .setId(l.getId())
                                                        .setName(l.getName())
                                                        .setColor(l.getColor())
                                                        .build())
                                            .toList()))
                            .setWatchers(
                                ListIssueWatchersResponse.newBuilder()
                                    .addAllWatchers(v.watchers())
                                    .setTotalCount(v.total()))
                            .setIsWatching(v.watching()))
                    .build());
  }

  private Mono<List<TenantIssueMetadataStore.Watcher>> visible(
      TenantIssueAccess.Permit p, UUID issue) {
    return store.watchers(p, issue).flatMap(rows -> filter(p.project(), rows));
  }

  private Mono<List<TenantIssueMetadataStore.Watcher>> filter(
      UUID project, List<TenantIssueMetadataStore.Watcher> rows) {
    return Flux.fromIterable(rows)
        .buffer(100)
        .concatMap(
            part ->
                eligibility
                    .check(
                        project,
                        part.stream().map(TenantIssueMetadataStore.Watcher::user).toList(),
                        true)
                    .map(ids -> part.stream().filter(w -> ids.contains(w.user())).toList()))
        .flatMapIterable(list -> list)
        .collectList();
  }

  private Mono<TenantIssueAccess.Permit> scope(
      String actor, String project, String issue, Action action) {
    return actor(actor)
        .then(store.issue(id(issue)))
        .flatMap(
            row -> {
              if (!row.project().equals(id(project)))
                return Mono.error(
                    new DomainException(DomainStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"));
              return access.authorize(action, row.project(), row.id());
            });
  }

  private Mono<Void> actor(String expected) {
    return VerifiedTenantContext.current()
        .flatMap(
            c ->
                expected.equals(c.actorId())
                    ? Mono.empty()
                    : Mono.error(
                        new DomainException(
                            DomainStatus.PERMISSION_DENIED, "ACTOR_CONTEXT_MISMATCH")));
  }

  static UUID id(String value) {
    try {
      var id = UUID.fromString(value);
      if (!id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw invalid("CANONICAL_UUID_REQUIRED");
    }
  }

  private static void validate(String name, String color) {
    if (name == null
        || name.isBlank()
        || name.length() > 50
        || color == null
        || !color.matches("#[0-9a-fA-F]{6}")) throw invalid("INVALID_LABEL");
  }

  private static DomainException invalid(String code) {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, code);
  }

  static ProjectLabelResponse label(TenantIssueMetadataStore.Label l) {
    var b =
        ProjectLabelResponse.newBuilder()
            .setId(l.id().toString())
            .setProjectId(l.project().toString())
            .setName(l.name())
            .setColor(l.color())
            .setCreatedBy(l.creator().toString())
            .setCreatedAt(TenantIssueApi.timestamp(l.created()));
    if (l.deleted() != null) b.setDeletedAt(TenantIssueApi.timestamp(l.deleted()));
    return b.build();
  }

  static IssueWatcherResponse watcher(TenantIssueMetadataStore.Watcher w) {
    return IssueWatcherResponse.newBuilder()
        .setId(w.id().toString())
        .setProjectId(w.project().toString())
        .setIssueId(w.issue().toString())
        .setUserId(w.user().toString())
        .setCreatedBy(w.creator().toString())
        .setCreatedAt(TenantIssueApi.timestamp(w.created()))
        .build();
  }
}

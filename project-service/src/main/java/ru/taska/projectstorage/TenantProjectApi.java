package ru.taska.projectstorage;

import java.time.*;
import java.util.*;
import java.util.function.*;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.*;
import ru.taska.api.project.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.TenantDecisionFacts.Frame;
import ru.taska.domain.Project;
import ru.taska.exception.*;
import ru.taska.mapper.ProjectMapper;
import ru.taska.security.tenant.*;
import ru.taska.service.ProjectInputValidator;
import tools.jackson.databind.ObjectMapper;

/** Native project boundary. Current primary principal precedes tenant-bound SQL and paging. */
public final class TenantProjectApi {
  public record Grant(UUID user, String role, long generation, Instant added) {}

  record Authorized(Project project, CapabilityFacts.ProjectRole role) {}

  public interface Facets {
    Mono<List<ProjectMemberDetailsResponse>> profiles(List<Grant> grants);

    Mono<ProjectWorkflowFacets> workflows(UUID project);

    Mono<ProjectLabelFacets> labels(UUID project);
  }

  private final TenantProjectStore store;
  private final TenantDecisionFacts facts;
  private final ManagedDomainReads reads;
  private final ProjectMapper mapper;
  private final Facets facets;
  private final ObjectMapper json;

  public TenantProjectApi(
      TenantProjectStore store,
      TenantDecisionFacts facts,
      ManagedDomainReads reads,
      ProjectMapper mapper,
      Facets facets,
      ObjectMapper json) {
    this.store = store;
    this.facts = facts;
    this.reads = reads;
    this.mapper = mapper;
    this.facets = facets;
    this.json = json;
  }

  public Mono<Frame> frame(String scope) {
    return facts.current(scope);
  }

  static UUID uuid(String id) {
    try {
      var v = UUID.fromString(id);
      if (!v.toString().equals(id)) throw new IllegalArgumentException();
      return v;
    } catch (RuntimeException e) {
      throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Canonical UUID required");
    }
  }

  public static void actor(Frame f, String actor) {
    if (actor == null || !actor.equals(f.context().actorId())) throw denied();
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
  }

  private static DomainException missing() {
    return new DomainException(DomainStatus.NOT_FOUND, "Project not found");
  }

  private UUID tenant(Frame f) {
    return uuid(f.context().tenantId());
  }

  private UUID user(Frame f) {
    return uuid(f.context().actorId());
  }

  private Mono<CapabilityFacts.ProjectRole> role(TenantProjectStore.Session s, Frame f, UUID id) {
    if (f.implicitAdmin()) return Mono.just(CapabilityFacts.ProjectRole.ADMIN);
    return s.database()
        .sql(
            "SELECT role FROM taska.project_members WHERE tenant_id=:t AND project_id=:p AND"
                + " user_id=:u AND root_membership_version=:g")
        .bind("t", tenant(f))
        .bind("p", id)
        .bind("u", user(f))
        .bind("g", Long.parseLong(f.membership().getVersion()))
        .map((r, m) -> CapabilityFacts.ProjectRole.valueOf(r.get("role", String.class)))
        .one()
        .defaultIfEmpty(CapabilityFacts.ProjectRole.NONE);
  }

  private Mono<Authorized> authorize(
      TenantProjectStore.Session s, Frame f, UUID id, Action action, boolean lock) {
    return (lock ? s.lock(id) : s.find(id))
        .switchIfEmpty(Mono.error(missing()))
        .flatMap(
            p ->
                role(s, f, id)
                    .flatMap(
                        role ->
                            facts
                                .decision(
                                    f,
                                    action,
                                    new CapabilityFacts.Target(tenant(f), id, null, null),
                                    role,
                                    lock && p.getArchivedAt() != null
                                        ? Set.of(ErrorCode.ERROR_CODE_INVALID_STATE)
                                        : Set.of())
                                .flatMap(
                                    decision ->
                                        audit(s, f, action, id, decision)
                                            .thenReturn(new Authorized(p, role)))));
  }

  private Mono<Void> audit(
      TenantProjectStore.Session s,
      Frame f,
      Action action,
      UUID project,
      CapabilityEvaluator.Decision decision) {
    if (!decision.auditRequired()) return Mono.empty();
    var q =
        s.database()
            .sql(
                "INSERT INTO"
                    + " taska.project_authorization_audit(tenant_id,actor_id,action,project_id,request_id,policy_version,decision)"
                    + " VALUES(:t,:a,:action,:p,:r,:v,CAST(:d AS jsonb))")
            .bind("t", tenant(f))
            .bind("a", user(f))
            .bind("action", action.wire())
            .bind("r", f.context().requestId())
            .bind("v", CapabilityEvaluator.POLICY_VERSION)
            .bind(
                "d",
                json.writeValueAsString(
                    Map.of(
                        "allowed",
                        true,
                        "businessBypass",
                        decision.businessBypass(),
                        "restrictions",
                        decision.bypassedRestrictions().stream().map(Enum::name).toList())));
    return (project == null ? q.bindNull("p", UUID.class) : q.bind("p", project)).then();
  }

  private ProjectResponse response(Authorized a) {
    return mapper.toProjectResponse(
        a.project(), ru.taska.domain.ProjectRole.valueOf(a.role().name()));
  }

  public Mono<ProjectResponse> get(String project, String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              return store.execute(
                  s ->
                      authorize(s, f, uuid(project), Action.PROJECT_READ, false)
                          .map(this::response));
            });
  }

  public Mono<CheckProjectMemberRoleResponse> check(String project, String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              return store.execute(
                  s ->
                      authorize(s, f, uuid(project), Action.PROJECT_READ, false)
                          .map(
                              a ->
                                  CheckProjectMemberRoleResponse.newBuilder()
                                      .setProjectExists(true)
                                      .setIsMember(true)
                                      .setRole(protoRole(a.role()))
                                      .setProjectArchived(a.project().getArchivedAt() != null)
                                      .build()));
            });
  }

  public Mono<ListMyProjectsResponse> list(String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              return store.execute(
                  s ->
                      s.database()
                          .sql(
                              visibleSql(f)
                                  + " AND p.archived_at IS NULL ORDER BY p.created_at,p.id")
                          .bind("t", tenant(f))
                          .bind("u", user(f))
                          .bind("g", Long.parseLong(f.membership().getVersion()))
                          .map((r, m) -> TenantProjectStore.project(r))
                          .all()
                          .concatMap(
                              p ->
                                  authorize(s, f, p.getId(), Action.PROJECT_READ, false)
                                      .map(this::response))
                          .collectList()
                          .map(
                              all ->
                                  ListMyProjectsResponse.newBuilder()
                                      .addAllProjectResponse(all)
                                      .build()));
            });
  }

  private String visibleSql(Frame f) {
    return "SELECT p.* FROM taska.projects p WHERE p.tenant_id=:t AND p.admission_state='OPEN' AND"
               + " (("
        + (f.implicitAdmin() ? "true" : "false")
        + ") OR EXISTS(SELECT 1 FROM taska.project_members m WHERE m.tenant_id=p.tenant_id AND"
        + " m.project_id=p.id AND m.user_id=:u AND m.root_membership_version=:g))";
  }

  public Mono<ProjectKeyResponse> key(String project) {
    return frame("read")
        .flatMap(
            f ->
                store.execute(
                    s ->
                        authorize(s, f, uuid(project), Action.PROJECT_READ, false)
                            .map(
                                a ->
                                    ProjectKeyResponse.newBuilder()
                                        .setProjectKey(a.project().getProjectKey())
                                        .build())));
  }

  public Mono<GetProjectInfoBatchInternalResponse> batch(List<String> projects) {
    if (projects.size() > 100)
      return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "At most 100 projects"));
    var ids = projects.stream().map(TenantProjectApi::uuid).distinct().toList();
    return frame("read")
        .flatMap(
            f -> {
              if (ids.isEmpty())
                return Mono.just(GetProjectInfoBatchInternalResponse.getDefaultInstance());
              return store.execute(
                  s ->
                      s.database()
                          .sql(visibleSql(f) + " AND p.id IN (:ids) ORDER BY p.id")
                          .bind("t", tenant(f))
                          .bind("u", user(f))
                          .bind("g", Long.parseLong(f.membership().getVersion()))
                          .bind("ids", ids)
                          .map((r, m) -> TenantProjectStore.project(r))
                          .all()
                          .concatMap(
                              p ->
                                  authorize(s, f, p.getId(), Action.PROJECT_READ, false)
                                      .then(
                                          s.types()
                                              .list(p.getId())
                                              .map(mapper::toIssueTypeResponse)
                                              .collectList())
                                      .map(
                                          types ->
                                              ProjectShortInfo.newBuilder()
                                                  .setId(p.getId().toString())
                                                  .setKey(p.getProjectKey())
                                                  .setName(p.getName())
                                                  .addAllIssueTypes(types)
                                                  .build()))
                          .collectMap(ProjectShortInfo::getId)
                          .map(
                              all ->
                                  GetProjectInfoBatchInternalResponse.newBuilder()
                                      .putAllProjects(all)
                                      .build()));
            });
  }

  public Mono<ListProjectIssueTypesResponse> types(String project, String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              return store.execute(
                  s ->
                      authorize(s, f, uuid(project), Action.ISSUE_TYPE_READ, false)
                          .then(
                              s.types()
                                  .list(uuid(project))
                                  .map(mapper::toIssueTypeResponse)
                                  .collectList())
                          .map(
                              all ->
                                  ListProjectIssueTypesResponse.newBuilder()
                                      .addAllItems(all)
                                      .build()));
            });
  }

  public Mono<ProjectResponse> update(UpdateProjectRequestBody q) {
    ProjectInputValidator.validate(
        q.hasName() ? Optional.of(q.getName()) : Optional.empty(),
        q.hasDescription() ? Optional.of(q.getDescription()) : Optional.empty(),
        q.hasColor() ? Optional.of(q.getColor()) : Optional.empty());
    if (q.getClearColor() && q.hasColor() || q.getClearDescription() && q.hasDescription())
      return Mono.error(new DomainException(DomainStatus.INVALID_ARGUMENT, "Conflicting patch"));
    return frame("write")
        .flatMap(
            f -> {
              actor(f, q.getActorUserId());
              return store.execute(
                  s ->
                      authorize(s, f, uuid(q.getProjectId()), Action.PROJECT_UPDATE, true)
                          .flatMap(
                              a -> {
                                var p = a.project().toBuilder();
                                if (q.hasName()) p.name(q.getName());
                                if (q.hasDescription() || q.getClearDescription())
                                  p.description(
                                      q.getClearDescription() || q.getDescription().isBlank()
                                          ? null
                                          : q.getDescription());
                                if (q.hasColor() || q.getClearColor())
                                  p.color(q.getClearColor() ? null : q.getColor());
                                return s.update(p.build())
                                    .flatMap(
                                        saved ->
                                            s.appendEvent(
                                                    saved.getId(),
                                                    "ProjectUpdated",
                                                    json.writeValueAsString(
                                                        new ru.taska
                                                            .event
                                                            .payload
                                                            .projectService
                                                            .ProjectUpdatedPayload(
                                                            saved.getId(),
                                                            saved.getName(),
                                                            saved.getDescription(),
                                                            saved.getColor(),
                                                            user(f))))
                                                .thenReturn(
                                                    response(new Authorized(saved, a.role()))));
                              }));
            });
  }

  private Mono<List<Grant>> grants(TenantProjectStore.Session s, Frame f, UUID project) {
    return s.database()
        .sql(
            "SELECT user_id,role,root_membership_version,added_at FROM taska.project_members WHERE"
                + " tenant_id=:t AND project_id=:p AND root_membership_version IS NOT NULL ORDER BY"
                + " user_id")
        .bind("t", tenant(f))
        .bind("p", project)
        .map(
            (r, m) ->
                new Grant(
                    r.get("user_id", UUID.class),
                    r.get("role", String.class),
                    r.get("root_membership_version", Long.class),
                    r.get("added_at", OffsetDateTime.class).toInstant()))
        .all()
        .collectList();
  }

  private Mono<List<Grant>> active(Frame f, List<Grant> grants) {
    if (grants.isEmpty()) return Mono.just(List.of());
    return Flux.fromIterable(grants)
        .buffer(100)
        .concatMap(
            part ->
                reads
                    .memberships(tenant(f), part.stream().map(Grant::user).toList())
                    .map(
                        result ->
                            part.stream()
                                .filter(
                                    g ->
                                        result.getItemsList().stream().anyMatch(m -> matches(g, m)))
                                .toList()))
        .flatMapIterable(Function.identity())
        .collectList();
  }

  private static boolean matches(Grant g, MembershipValidation m) {
    return m.getUserId().equals(g.user().toString())
        && m.getFound()
        && m.hasMembership()
        && m.getMembership().getState() == Membership.StateValue.STATE_ACTIVE
        && m.getMembership().getVersion().equals(Long.toString(g.generation()));
  }

  public Mono<ListProjectMemberResponse> members(String project, String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              var id = uuid(project);
              return store
                  .execute(
                      s -> authorize(s, f, id, Action.PROJECT_READ, false).then(grants(s, f, id)))
                  .flatMap(all -> active(f, all))
                  .flatMap(facets::profiles)
                  .map(all -> ListProjectMemberResponse.newBuilder().addAllMembers(all).build());
            });
  }

  record ContextCore(
      ProjectResponse project, List<Grant> grants, List<ProjectIssueTypeResponse> types) {}

  public Mono<ProjectContextResponse> context(String project, String actor) {
    return frame("read")
        .flatMap(
            f -> {
              actor(f, actor);
              UUID id = uuid(project);
              return store
                  .execute(
                      s ->
                          authorize(s, f, id, Action.PROJECT_READ, false)
                              .flatMap(
                                  a ->
                                      s.types()
                                          .list(id)
                                          .map(mapper::toIssueTypeResponse)
                                          .collectList()
                                          .flatMap(
                                              types ->
                                                  grants(s, f, id)
                                                      .map(
                                                          members ->
                                                              new ContextCore(
                                                                  response(a), members, types)))))
                  .flatMap(
                      core ->
                          Mono.zip(
                                  active(f, core.grants()).flatMap(facets::profiles),
                                  facets.workflows(id),
                                  facets.labels(id))
                              .map(
                                  v ->
                                      ProjectContextResponse.newBuilder()
                                          .setProject(core.project())
                                          .addAllMembers(v.getT1())
                                          .addAllIssueTypes(core.types())
                                          .addAllWorkflows(v.getT2().getItemsList())
                                          .addAllLabels(v.getT3().getItemsList())
                                          .build()));
            });
  }

  public Mono<GetProjectMemberStatsBatchResponse> stats(List<String> projects, String actor) {
    return batch(projects)
        .flatMap(
            found ->
                Flux.fromIterable(found.getProjectsMap().keySet())
                    .concatMap(
                        id ->
                            members(id, actor)
                                .map(
                                    all ->
                                        ProjectMemberStats.newBuilder()
                                            .setProjectId(id)
                                            .setMemberCount(all.getMembersCount())
                                            .addAllMembers(all.getMembersList())
                                            .build()))
                    .collectList()
                    .map(
                        all ->
                            GetProjectMemberStatsBatchResponse.newBuilder()
                                .addAllItems(all)
                                .build()));
  }

  private Mono<Long> target(Frame f, UUID user) {
    return reads
        .memberships(tenant(f), List.of(user))
        .map(
            result -> {
              var m = result.getItems(0);
              if (!m.getFound()
                  || !m.hasMembership()
                  || m.getMembership().getState() != Membership.StateValue.STATE_ACTIVE)
                throw new DomainException(
                    DomainStatus.NOT_FOUND, "Active company member not found");
              return Long.parseLong(m.getMembership().getVersion());
            });
  }

  public Mono<Void> change(
      String project,
      String actor,
      String target,
      ru.taska.api.project.v1.ProjectRole requested,
      String operation) {
    UUID id = uuid(project), member = uuid(target);
    String role =
        operation.equals("REMOVE")
            ? "VIEWER"
            : switch (requested) {
              case PROJECT_ROLE_ADMIN -> "ADMIN";
              case PROJECT_ROLE_MEMBER -> "MEMBER";
              case PROJECT_ROLE_VIEWER -> "VIEWER";
              default ->
                  throw new DomainException(DomainStatus.INVALID_ARGUMENT, "Invalid project role");
            };
    return frame("write")
        .flatMap(
            f -> {
              actor(f, actor);
              // Obtain primary membership facts outside the project lock. SQL still checks
              // authorization again.
              return store
                  .execute(
                      s ->
                          authorize(s, f, id, Action.PROJECT_MEMBERSHIP_UPDATE, false)
                              .then(grants(s, f, id)))
                  .flatMap(
                      all ->
                          Mono.zip(
                              active(f, all),
                              operation.equals("REMOVE") ? Mono.just(0L) : target(f, member)))
                  .flatMap(
                      primary ->
                          store.execute(
                              s ->
                                  authorize(s, f, id, Action.PROJECT_MEMBERSHIP_UPDATE, true)
                                      .flatMap(
                                          a ->
                                              grants(s, f, id)
                                                  .flatMap(
                                                      current -> {
                                                        var old =
                                                            current.stream()
                                                                .filter(
                                                                    g -> g.user().equals(member))
                                                                .findFirst();
                                                        if (!operation.equals("ADD")
                                                            && old.isEmpty())
                                                          return Mono.error(
                                                              new DomainException(
                                                                  DomainStatus.NOT_FOUND,
                                                                  "Project member not found"));
                                                        if (operation.equals("ADD")
                                                            && old.isPresent()
                                                            && old.get().generation()
                                                                == primary.getT2())
                                                          return Mono.error(
                                                              new DomainException(
                                                                  DomainStatus.ALREADY_EXISTS,
                                                                  "Project member exists"));
                                                        if (old.isPresent()
                                                            && old.get().role().equals("ADMIN")
                                                            && !role.equals("ADMIN")) {
                                                          long others =
                                                              current.stream()
                                                                  .filter(
                                                                      g ->
                                                                          !g.user().equals(member)
                                                                              && g.role()
                                                                                  .equals("ADMIN")
                                                                              && primary
                                                                                  .getT1()
                                                                                  .contains(g))
                                                                  .count();
                                                          if (others == 0)
                                                            return Mono.error(
                                                                new DomainException(
                                                                    DomainStatus
                                                                        .FAILED_PRECONDITION,
                                                                    "Last project ADMIN required"));
                                                        }
                                                        Mono<Void> write;
                                                        if (operation.equals("REMOVE"))
                                                          write = s.removeMember(id, member).then();
                                                        else
                                                          write =
                                                              s.database()
                                                                  .sql(
                                                                      "INSERT INTO"
                                                                          + " taska.project_members(tenant_id,project_id,user_id,role,added_by,root_membership_version)"
                                                                          + " VALUES(:t,:p,:u,:role,:by,:g)"
                                                                          + " ON CONFLICT(tenant_id,project_id,user_id)"
                                                                          + " DO UPDATE SET"
                                                                          + " role=EXCLUDED.role,root_membership_version=EXCLUDED.root_membership_version,added_by=EXCLUDED.added_by,added_at=now()")
                                                                  .bind("t", tenant(f))
                                                                  .bind("p", id)
                                                                  .bind("u", member)
                                                                  .bind("role", role)
                                                                  .bind("by", user(f))
                                                                  .bind("g", primary.getT2())
                                                                  .then();
                                                        Object payload =
                                                            switch (operation) {
                                                              case "ADD" ->
                                                                  new ru.taska
                                                                      .event
                                                                      .payload
                                                                      .projectService
                                                                      .MemberAddedPayload(
                                                                      id,
                                                                      member,
                                                                      role,
                                                                      user(f),
                                                                      a.project().getProjectKey());
                                                              case "REMOVE" ->
                                                                  new ru.taska
                                                                      .event
                                                                      .payload
                                                                      .projectService
                                                                      .MemberRemovedPayload(
                                                                      id,
                                                                      member,
                                                                      a.project().getProjectKey());
                                                              default ->
                                                                  new ru.taska
                                                                      .event
                                                                      .payload
                                                                      .projectService
                                                                      .MemberUpdatedPayload(
                                                                      member,
                                                                      role,
                                                                      id,
                                                                      a.project().getProjectKey());
                                                            };
                                                        return write.then(
                                                            s.appendEvent(
                                                                id,
                                                                operation.equals("ADD")
                                                                    ? "MemberAdded"
                                                                    : operation.equals("REMOVE")
                                                                        ? "MemberRemoved"
                                                                        : "MemberUpdated",
                                                                json.writeValueAsString(payload)));
                                                      }))));
            });
  }

  static ru.taska.api.project.v1.ProjectRole protoRole(CapabilityFacts.ProjectRole r) {
    return ru.taska.api.project.v1.ProjectRole.valueOf("PROJECT_ROLE_" + r.name());
  }
}

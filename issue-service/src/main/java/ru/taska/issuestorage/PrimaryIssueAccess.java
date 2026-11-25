package ru.taska.issuestorage;

import com.google.protobuf.Message;
import io.grpc.ClientInterceptor;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.b2b.v1.Membership;
import ru.taska.api.b2b.v1.QuotaLimit;
import ru.taska.api.b2b.v1.RequestContext;
import ru.taska.api.project.v1.GetListProjectMemberRequest;
import ru.taska.api.project.v1.GetListProjectMemberRequestBody;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalRequest;
import ru.taska.api.project.v1.GetProjectInfoBatchInternalRequestBody;
import ru.taska.api.project.v1.GetProjectKeyInternalRequest;
import ru.taska.api.project.v1.GetProjectKeyInternalRequestBody;
import ru.taska.api.project.v1.ProjectServiceGrpc;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody;
import ru.taska.api.workflow.v1.IssueValidateSnapshot;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.ValidateTransitionRequest;
import ru.taska.api.workflow.v1.ValidateTransitionRequestBody;
import ru.taska.api.workflow.v1.WorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.capability.Action;
import ru.taska.capability.CapabilityFacts;
import ru.taska.capability.ManagedDomainReads;
import ru.taska.capability.ProjectAccessClient;
import ru.taska.capability.TenantDecisionFacts;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.quota.SignedQuotaClient;
import ru.taska.security.tenant.RpcContextSecurity;
import ru.taska.security.tenant.VerifiedTenantContext;

/**
 * Роль, ключ проекта, workflow и членство исполнителя проверяются в сервисах-владельцах, а не
 * берутся из запроса задачи.
 */
public final class PrimaryIssueAccess implements TenantIssueAccess {
  private final TenantDecisionFacts facts;
  private final ManagedDomainReads reads;
  private final ProjectAccessClient projects;
  private final SignedQuotaClient projectRpc, workflowRpc;

  public PrimaryIssueAccess(
      TenantDecisionFacts facts, ManagedDomainReads reads, RpcContextSecurity security) {
    this.facts = facts;
    this.reads = reads;
    projects = new ProjectAccessClient(reads, security, "issue-service");
    projectRpc =
        new SignedQuotaClient(security, Clock.systemUTC(), "issue-service", "project-service");
    workflowRpc =
        new SignedQuotaClient(security, Clock.systemUTC(), "issue-service", "workflow-service");
  }

  public Mono<Permit> authorize(Action action, UUID project, UUID issue) {
    boolean write =
        action != Action.ISSUE_READ
            && action != Action.ISSUE_HISTORY
            && action != Action.ISSUE_SEARCH
            && action != Action.LABEL_READ
            && action != Action.WATCHER_READ
            && action != Action.ATTACHMENT_READ;
    boolean metadata =
        action.name().startsWith("LABEL_")
            || action.name().startsWith("WATCHER_")
            || action.name().startsWith("ATTACHMENT_");
    return (metadata
            ? facts.currentAdministrative(write ? "write" : "read")
            : facts.current(write ? "write" : "read"))
        .flatMap(
            frame ->
                projects
                    .check(project)
                    .flatMap(
                        access -> {
                          if (write && !access.hasProjectArchived())
                            return Mono.error(
                                fail(DomainStatus.UNAVAILABLE, "ARCHIVE_AUTHORITY_UNAVAILABLE"));
                          var role =
                              switch (access.getRole()) {
                                case PROJECT_ROLE_ADMIN -> CapabilityFacts.ProjectRole.ADMIN;
                                case PROJECT_ROLE_MEMBER -> CapabilityFacts.ProjectRole.MEMBER;
                                case PROJECT_ROLE_VIEWER -> CapabilityFacts.ProjectRole.VIEWER;
                                default -> CapabilityFacts.ProjectRole.NONE;
                              };
                          var target =
                              new CapabilityFacts.Target(
                                  UUID.fromString(frame.context().tenantId()),
                                  project,
                                  issue,
                                  null);
                          return facts
                              .decision(
                                  frame,
                                  action,
                                  target,
                                  role,
                                  write && access.getProjectArchived()
                                      ? Set.of(ErrorCode.ERROR_CODE_INVALID_STATE)
                                      : Set.of())
                              .map(
                                  d ->
                                      new Permit(
                                          target.tenant(),
                                          UUID.fromString(frame.context().actorId()),
                                          frame.context().action(),
                                          project,
                                          frame.until(),
                                          d.businessBypass(),
                                          d.auditRequired(),
                                          d.bypassedRestrictions().stream()
                                              .map(Enum::name)
                                              .collect(java.util.stream.Collectors.toSet())));
                        }));
  }

  @Override
  public Mono<ReadBatch> readMany(List<UUID> ids) {
    return facts
        .current("read")
        .flatMap(
            frame ->
                call(
                        projectRpc,
                        ProjectServiceGrpc.getGetProjectInfoBatchInternalMethod()
                            .getFullMethodName(),
                        "getProjectInfoBatchInternal",
                        c ->
                            GetProjectInfoBatchInternalRequest.newBuilder()
                                .setContext(c)
                                .setHeader(c.getHeader())
                                .setBody(
                                    GetProjectInfoBatchInternalRequestBody.newBuilder()
                                        .addAllProjectIds(
                                            ids.stream().map(UUID::toString).toList()))
                                .build(),
                        (q, a) ->
                            ReactorProjectServiceGrpc.newReactorStub(
                                    reads.channel("project-service"))
                                .withInterceptors(a)
                                .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                                .getProjectInfoBatchInternal(q))
                    .flatMap(
                        result -> {
                          if (!result
                              .getProjectsMap()
                              .keySet()
                              .equals(
                                  ids.stream()
                                      .map(UUID::toString)
                                      .collect(java.util.stream.Collectors.toSet())))
                            return Mono.error(fail(DomainStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
                          var limits =
                              frame.entitlement().getLimitsList().stream()
                                  .filter(
                                      x -> x.getKind() == QuotaLimit.KindValue.KIND_TASK_LIFETIME)
                                  .toList();
                          if (limits.size() != 1
                              || (!limits.get(0).getUnlimited() && !limits.get(0).hasLimit()))
                            return Mono.error(
                                fail(DomainStatus.UNAVAILABLE, "TASK_LIMIT_UNAVAILABLE"));
                          var limit = limits.get(0);
                          Long maximum =
                              limit.getUnlimited() ? null : Long.valueOf(limit.getLimit());
                          if (maximum != null && maximum < 0)
                            return Mono.error(
                                fail(DomainStatus.UNAVAILABLE, "TASK_LIMIT_UNAVAILABLE"));
                          return reactor.core.publisher.Flux.fromIterable(ids)
                              .concatMap(
                                  id -> {
                                    var target =
                                        new CapabilityFacts.Target(
                                            UUID.fromString(frame.context().tenantId()),
                                            id,
                                            null,
                                            null);
                                    // Сервис-владелец подтвердил чтение проекта этим пользователем.
                                    // VIEWER обозначает минимальные права чтения и не разрешает
                                    // изменение проекта.
                                    return facts
                                        .decision(
                                            frame,
                                            Action.ISSUE_SEARCH,
                                            target,
                                            CapabilityFacts.ProjectRole.VIEWER,
                                            Set.of())
                                        .map(
                                            d ->
                                                new Permit(
                                                    target.tenant(),
                                                    UUID.fromString(frame.context().actorId()),
                                                    frame.context().action(),
                                                    id,
                                                    frame.until(),
                                                    d.businessBypass(),
                                                    d.auditRequired(),
                                                    d.bypassedRestrictions().stream()
                                                        .map(Enum::name)
                                                        .collect(
                                                            java.util.stream.Collectors.toSet())));
                                  })
                              .collectList()
                              .map(
                                  permits -> new ReadBatch(permits, maximum, limit.getUnlimited()));
                        }));
  }

  public Mono<String> projectKey(UUID project) {
    return call(
            projectRpc,
            ProjectServiceGrpc.getGetProjectKeyInternalMethod().getFullMethodName(),
            "getProjectKeyInternal",
            c ->
                GetProjectKeyInternalRequest.newBuilder()
                    .setContext(c)
                    .setHeader(c.getHeader())
                    .setBody(
                        GetProjectKeyInternalRequestBody.newBuilder()
                            .setProjectId(project.toString()))
                    .build(),
            (q, a) ->
                ReactorProjectServiceGrpc.newReactorStub(reads.channel("project-service"))
                    .withInterceptors(a)
                    .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                    .getProjectKeyInternal(q))
        .map(
            r -> {
              if (!r.getProjectKey().matches("[A-Z][A-Z0-9]{1,15}"))
                throw fail(DomainStatus.UNAVAILABLE, "INVALID_PROJECT_KEY");
              return r.getProjectKey();
            });
  }

  public Mono<String> initialStatus(UUID project, String type, String requested) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent ->
                call(
                    workflowRpc,
                    WorkflowServiceGrpc.getGetWorkflowForProjectMethod().getFullMethodName(),
                    "getWorkflowForProject",
                    c ->
                        GetWorkflowForProjectRequest.newBuilder()
                            .setContext(c)
                            .setHeader(c.getHeader())
                            .setBody(
                                GetWorkflowForProjectRequestBody.newBuilder()
                                    .setProjectId(project.toString())
                                    .setActorUserId(parent.actorId())
                                    .setIssueTypeKey(type))
                            .build(),
                    (q, a) ->
                        ReactorWorkflowServiceGrpc.newReactorStub(reads.channel("workflow-service"))
                            .withInterceptors(a)
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .getWorkflowForProject(q)))
        .flatMap(
            w -> {
              if (requested != null)
                return w.getStatusesList().stream()
                        .anyMatch(s -> s.getStatusKey().equals(requested))
                    ? Mono.just(requested)
                    : Mono.error(fail(DomainStatus.INVALID_ARGUMENT, "STATUS_NOT_IN_WORKFLOW"));
              return Mono.justOrEmpty(
                      w.getStatusesList().stream()
                          .filter(s -> s.getCategory() == StatusCategory.STATUS_CATEGORY_TODO)
                          .min(
                              java.util.Comparator.comparingInt(WorkflowStatus::getSortOrder)
                                  .thenComparing(WorkflowStatus::getId))
                          .map(WorkflowStatus::getStatusKey))
                  .switchIfEmpty(
                      Mono.error(fail(DomainStatus.UNAVAILABLE, "INITIAL_STATUS_UNAVAILABLE")));
            });
  }

  public Mono<String> transition(TenantIssueStore.Snapshot issue, UUID transition, String payload) {
    return VerifiedTenantContext.current()
        .flatMap(
            parent ->
                call(
                    workflowRpc,
                    WorkflowServiceGrpc.getValidateTransitionMethod().getFullMethodName(),
                    "validateTransition",
                    c ->
                        ValidateTransitionRequest.newBuilder()
                            .setContext(c)
                            .setHeader(c.getHeader())
                            .setBody(
                                ValidateTransitionRequestBody.newBuilder()
                                    .setActorUserId(parent.actorId())
                                    .setTransitionId(transition.toString())
                                    .setPayload(payload)
                                    .setIssueSnapshot(
                                        IssueValidateSnapshot.newBuilder()
                                            .setIssueId(issue.identity().id().toString())
                                            .setProjectId(issue.identity().projectId().toString())
                                            .setIssueTypeKey(issue.issueType())
                                            .setStatusKey(issue.statusKey())))
                            .build(),
                    (q, a) ->
                        ReactorWorkflowServiceGrpc.newReactorStub(reads.channel("workflow-service"))
                            .withInterceptors(a)
                            .withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS)
                            .validateTransition(q)))
        .map(
            r -> {
              if (!r.getBody().getIsValid() || r.getBody().getToStatusKey().isBlank())
                throw fail(DomainStatus.FAILED_PRECONDITION, "TRANSITION_DENIED");
              return r.getBody().getToStatusKey();
            });
  }

  public Mono<Void> assignee(UUID project, UUID user) {
    if (user == null) return Mono.empty();
    return VerifiedTenantContext.current()
        .flatMap(
            parent ->
                reads
                    .memberships(UUID.fromString(parent.tenantId()), List.of(user))
                    .flatMap(
                        batch -> {
                          var item = batch.getItems(0);
                          if (!item.getFound()
                              || !item.hasMembership()
                              || item.getMembership().getState()
                                  != Membership.StateValue.STATE_ACTIVE)
                            return Mono.error(
                                fail(DomainStatus.INVALID_ARGUMENT, "ASSIGNEE_NOT_ACTIVE"));
                          if (item.getMembership().getRole()
                              == Membership.RoleValue.ROLE_TENANT_ADMIN) return Mono.empty();
                          return call(
                                  projectRpc,
                                  ProjectServiceGrpc.getGetProjectMembersMethod()
                                      .getFullMethodName(),
                                  "getProjectMembers",
                                  c ->
                                      GetListProjectMemberRequest.newBuilder()
                                          .setContext(c)
                                          .setHeader(c.getHeader())
                                          .setBody(
                                              GetListProjectMemberRequestBody.newBuilder()
                                                  .setProjectId(project.toString())
                                                  .setActorUserId(parent.actorId()))
                                          .build(),
                                  (q, a) ->
                                      ReactorProjectServiceGrpc.newReactorStub(
                                              reads.channel("project-service"))
                                          .withInterceptors(a)
                                          .withDeadlineAfter(
                                              5, java.util.concurrent.TimeUnit.SECONDS)
                                          .getProjectMembers(q))
                              .flatMap(
                                  list ->
                                      list.getMembersList().stream()
                                              .anyMatch(m -> m.getUserId().equals(user.toString()))
                                          ? Mono.<Void>empty()
                                          : Mono.error(
                                              fail(
                                                  DomainStatus.INVALID_ARGUMENT,
                                                  "ASSIGNEE_NOT_PROJECT_MEMBER")));
                        }));
  }

  private <Q extends Message, R> Mono<R> call(
      SignedQuotaClient client,
      String rpc,
      String action,
      Function<RequestContext, Q> body,
      BiFunction<Q, ClientInterceptor, Mono<R>> send) {
    return VerifiedTenantContext.current()
        .flatMap(c -> client.call(UUID.fromString(c.tenantId()), rpc, action, body, send));
  }

  private static DomainException fail(DomainStatus status, String code) {
    return new DomainException(status, code);
  }
}

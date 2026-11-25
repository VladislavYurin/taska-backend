package ru.taska.workflowstorage;

import com.google.protobuf.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiFunction;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.ErrorCode;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.workflow.v1.*;
import ru.taska.capability.*;
import ru.taska.capability.TenantDecisionFacts.Frame;
import ru.taska.dto.*;
import ru.taska.exception.*;
import ru.taska.service.WorkflowCreationValidator;
import ru.taska.service.WorkflowMutationService.Operation;
import ru.taska.workflowstorage.TenantWorkflowStore.*;
import tools.jackson.databind.ObjectMapper;

/** Native workflow boundary. Primary authority precedes SQL; SQL callbacks never perform RPCs. */
public final class TenantWorkflowApi {
  record Authorized(
      Frame frame,
      UUID project,
      List<ProjectIssueTypeResponse> types,
      CapabilityEvaluator.Decision decision) {}

  record Principal(
      Frame frame,
      UUID project,
      CapabilityFacts.ProjectRole role,
      CapabilityFacts.Target target,
      Set<ErrorCode> integrity) {}

  record Graph(
      Workflow workflow,
      List<Status> statuses,
      List<Transition> transitions,
      WorkflowResponse response) {}

  private final TenantWorkflowStore store;
  private final TenantDecisionFacts facts;
  private final WorkflowProjectAccess projects;
  private final ObjectMapper json;
  private final WorkflowCreationValidator validator;

  public TenantWorkflowApi(
      TenantWorkflowStore store,
      TenantDecisionFacts facts,
      WorkflowProjectAccess projects,
      ObjectMapper json) {
    this.store = store;
    this.facts = facts;
    this.projects = projects;
    this.json = json;
    validator = new WorkflowCreationValidator(100, "^[A-Z][A-Z0-9_]{1,63}$");
  }

  private Mono<Principal> principal(String project, String actor, Action action, String issue) {
    boolean write = action != Action.WORKFLOW_READ;
    return facts
        .current(write ? "write" : "read")
        .flatMap(
            frame -> {
              if (!frame.context().actorId().equals(actor)) return Mono.error(denied());
              UUID id = uuid(project);
              return projects
                  .check(id)
                  .map(
                      access -> {
                        if (!access.getProjectExists() || !access.getIsMember()) throw missing();
                        if (write && !access.hasProjectArchived())
                          throw unavailable("Archive-aware project authority required");
                        var role =
                            switch (access.getRole()) {
                              case PROJECT_ROLE_ADMIN -> CapabilityFacts.ProjectRole.ADMIN;
                              case PROJECT_ROLE_MEMBER -> CapabilityFacts.ProjectRole.MEMBER;
                              case PROJECT_ROLE_VIEWER -> CapabilityFacts.ProjectRole.VIEWER;
                              default -> CapabilityFacts.ProjectRole.NONE;
                            };
                        var target =
                            new CapabilityFacts.Target(
                                uuid(frame.context().tenantId()),
                                id,
                                issue == null ? null : uuid(issue),
                                null);
                        return new Principal(
                            frame,
                            id,
                            role,
                            target,
                            write && access.getProjectArchived()
                                ? Set.of(ErrorCode.ERROR_CODE_INVALID_STATE)
                                : Set.of());
                      });
            });
  }

  private Mono<Authorized> registry(Principal p, CapabilityEvaluator.Decision decision) {
    return projects
        .types(p.project())
        .map(
            registry -> {
              if (registry.getItemsCount() > 5000) throw invalid("Project registry is too large");
              var keys = new HashSet<String>();
              for (var type : registry.getItemsList()) {
                type(type.getCode());
                uuid(type.getGeneration());
                if (!keys.add(type.getCode()))
                  throw unavailable("Invalid current project registry");
                if (!type.getPendingOperationId().isBlank()) throw pending();
              }
              return new Authorized(p.frame(), p.project(), registry.getItemsList(), decision);
            });
  }

  private Mono<Authorized> authorize(
      String project, String actor, Action action, String issue, boolean bindings) {
    return principal(project, actor, action, issue)
        .flatMap(p -> decision(p, action, bindings, null).flatMap(d -> registry(p, d)));
  }

  private Mono<CapabilityEvaluator.Decision> decision(
      Principal p, Action action, boolean bindings, UUID receipt) {
    return oneDecision(p, action, receipt)
        .flatMap(
            d ->
                bindings
                    ? oneDecision(p, Action.ISSUE_TYPE_CONFIGURE, receipt)
                        .map(
                            other -> {
                              var causes = new HashSet<>(d.bypassedRestrictions());
                              causes.addAll(other.bypassedRestrictions());
                              return new CapabilityEvaluator.Decision(
                                  d.capability(),
                                  d.auditRequired() || other.auditRequired(),
                                  d.businessBypass() || other.businessBypass(),
                                  causes);
                            })
                    : Mono.just(d));
  }

  /** A completed local receipt pins only its action flags, never current principal/ACL/channel. */
  private Mono<CapabilityEvaluator.Decision> oneDecision(Principal p, Action action, UUID receipt) {
    if (receipt == null)
      return facts.decision(p.frame(), action, p.target(), p.role(), p.integrity());
    var target = new CapabilityFacts.Target(p.target().tenant(), p.project(), null, receipt);
    var flag =
        action == Action.WORKFLOW_CONFIGURE
            ? CapabilityFacts.Flag.WORKFLOW_CONFIGURATION
            : CapabilityFacts.Flag.ISSUE_TYPE_CONFIGURATION;
    var accepted = new CapabilityFacts.Accepted(receipt, target, Set.of(action), Set.of(flag));
    return new CapabilityEvaluator(
            (c, t, actions) -> {
              if (c != p.frame().context() || !target.equals(t)) return Mono.error(denied());
              return Mono.just(
                  p.frame().facts(t, p.role(), actions, Map.of(action, p.integrity()), accepted));
            },
            java.time.Clock.systemUTC())
        .evaluate(action, target)
        .map(
            d -> {
              if (!d.capability().getAllowed()) {
                String code =
                    d.capability().getDenialCodesList().stream()
                        .findFirst()
                        .orElse(ErrorCode.ERROR_CODE_SCOPE_DENIED)
                        .name()
                        .replace("ERROR_CODE_", "");
                throw new DomainException(
                    code.equals("RESOURCE_NOT_FOUND")
                        ? DomainStatus.NOT_FOUND
                        : DomainStatus.PERMISSION_DENIED,
                    code,
                    Map.of("code", code));
              }
              return d;
            });
  }

  private <T> Mono<T> read(Authorized a, BiFunction<Session, Authorized, Mono<T>> work) {
    return store.execute(
        s ->
            s.lockProjectRead(a.project())
                .then(s.requireReadable(a.project()))
                .then(audit(s, a))
                .then(Mono.defer(() -> work.apply(s, a))));
  }

  private <T> Mono<T> write(Authorized a, BiFunction<Session, Authorized, Mono<T>> work) {
    return store.execute(
        s ->
            s.lockProject(a.project())
                .then(s.requireWritable(a.project()))
                .then(
                    Mono.defer(
                        () ->
                            java.time.Instant.now().isBefore(a.frame().until())
                                ? Mono.empty()
                                : Mono.error(
                                    unavailable(
                                        "Current authorization expired while waiting for workflow"
                                            + " lock"))))
                .then(audit(s, a))
                .then(Mono.defer(() -> work.apply(s, a))));
  }

  private Mono<Void> audit(Session s, Authorized a) {
    if (a.decision() == null || !a.decision().auditRequired()) return Mono.empty();
    return s.database()
        .sql(
            """
            INSERT INTO taska.workflow_authorization_audit(tenant_id,actor_id,project_id,action,request_id,policy_version,decision)
            VALUES(:t,:u,:p,:a,:r,:v,CAST(:d AS jsonb))
            """)
        .bind("t", uuid(a.frame().context().tenantId()))
        .bind("u", uuid(a.frame().context().actorId()))
        .bind("p", a.project())
        .bind("a", a.frame().context().action())
        .bind("r", a.frame().context().requestId())
        .bind("v", CapabilityEvaluator.POLICY_VERSION)
        .bind(
            "d",
            json.writeValueAsString(
                Map.of(
                    "allowed",
                    true,
                    "businessBypass",
                    a.decision().businessBypass(),
                    "restrictions",
                    a.decision().bypassedRestrictions().stream().map(Enum::name).toList())))
        .then();
  }

  public Mono<WorkflowResponse> get(GetWorkflowForProjectRequestBody q) {
    return authorize(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_READ, null, false)
        .flatMap(
            a ->
                read(
                    a,
                    (s, p) ->
                        resolve(
                                s,
                                p,
                                key(q.hasIssueTypeKey(), q.getIssueTypeKey(), q.getIssueType()))
                            .flatMap(w -> graph(s, w))
                            .map(
                                g ->
                                    g.response().toBuilder()
                                        .clearTransitions()
                                        .addAllTransitions(
                                            g.response().getTransitionsList().stream()
                                                .filter(t -> !t.getIsHidden())
                                                .toList())
                                        .build())));
  }

  public Mono<ListProjectWorkflowsResponse> list(ListProjectWorkflowsRequestBody q) {
    return authorize(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_READ, null, false)
        .flatMap(
            a ->
                read(
                    a,
                    (s, p) ->
                        Flux.fromIterable(p.types())
                            .concatMap(t -> resolve(s, p, t.getCode()))
                            .distinct(Workflow::id)
                            .concatMap(w -> graph(s, w).flatMap(g -> response(s, p, g)))
                            .collectList()
                            .map(
                                all -> {
                                  var result =
                                      ListProjectWorkflowsResponse.newBuilder()
                                          .addAllWorkflows(all);
                                  if (!all.isEmpty())
                                    result.setMainWorkflowId(all.get(0).getWorkflow().getId());
                                  return result.build();
                                })));
  }

  public Mono<Void> scope(CheckProjectWorkflowScopeRequest q) {
    return authorize(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_READ, null, false)
        .flatMap(
            a -> {
              if (q.getIssueTypeKeysCount() == 0
                  || q.getIssueTypeKeysCount() > 5000
                  || q.hasExpectedWorkflowId() != q.hasExpectedWorkflowVersion()
                  || q.hasExpectedWorkflowVersion() && q.getExpectedWorkflowVersion() < 1)
                return Mono.error(
                    invalid("A bounded issue-type scope and paired workflow/version are required"));
              UUID expected = q.hasExpectedWorkflowId() ? uuid(q.getExpectedWorkflowId()) : null;
              if (q.hasStatusKey()) statusKey(q.getStatusKey());
              return read(
                  a,
                  (s, p) ->
                      Flux.fromIterable(q.getIssueTypeKeysList())
                          .distinct()
                          .concatMap(
                              code ->
                                  resolve(s, p, code)
                                      .flatMap(
                                          w -> {
                                            if (expected != null
                                                && (!expected.equals(w.id())
                                                    || q.getExpectedWorkflowVersion()
                                                        != w.version()))
                                              return Mono.error(changed());
                                            return q.hasStatusKey()
                                                ? s.statuses(w.id())
                                                    .any(n -> n.key().equals(q.getStatusKey()))
                                                    .flatMap(
                                                        found ->
                                                            found
                                                                ? Mono.empty()
                                                                : Mono.error(
                                                                    new DomainException(
                                                                        DomainStatus
                                                                            .FAILED_PRECONDITION,
                                                                        "Status is not in the"
                                                                            + " workflow",
                                                                        Map.of(
                                                                            "reason",
                                                                            "STATUS_NOT_IN_WORKFLOW"))))
                                                : Mono.empty();
                                          }))
                          .then());
            });
  }

  public Mono<ValidateTransitionResponseBody> validate(ValidateTransitionRequestBody q) {
    var snapshot = q.getIssueSnapshot();
    return authorize(
            snapshot.getProjectId(),
            q.getActorUserId(),
            Action.ISSUE_TRANSITION,
            snapshot.getIssueId(),
            false)
        .flatMap(
            a ->
                read(
                    a,
                    (s, p) -> {
                      UUID transition = uuid(q.getTransitionId());
                      return resolve(
                              s,
                              p,
                              key(
                                  snapshot.hasIssueTypeKey(),
                                  snapshot.getIssueTypeKey(),
                                  snapshot.getIssueType()))
                          .flatMap(w -> graph(s, w))
                          .map(
                              g -> {
                                var edge =
                                    g.transitions().stream()
                                        .filter(t -> t.id().equals(transition))
                                        .findFirst();
                                if (edge.isEmpty()) return violation("TRANSITION_NOT_FOUND");
                                var current =
                                    g.statuses().stream()
                                        .filter(n -> n.key().equals(snapshot.getStatusKey()))
                                        .findFirst();
                                if (current.isEmpty()) return violation("CURRENT_STATUS_NOT_FOUND");
                                if (!current.get().id().equals(edge.get().from()))
                                  return violation("FROM_STATUS_DOESNT_MATCH");
                                var target =
                                    g.statuses().stream()
                                        .filter(n -> n.id().equals(edge.get().to()))
                                        .findFirst();
                                if (target.isEmpty()) return violation("TARGET_STATUS_NOT_FOUND");
                                return ValidateTransitionResponseBody.newBuilder()
                                    .setIsValid(true)
                                    .setToStatusKey(target.get().key())
                                    .build();
                              });
                    }));
  }

  private static ValidateTransitionResponseBody violation(String reason) {
    return ValidateTransitionResponseBody.newBuilder()
        .setIsValid(false)
        .setToStatusKey("ISSUE_STATUS_UNSPECIFIED")
        .addTransitionViolations(
            ru.taska.api.workflow.v1.TransitionViolation.newBuilder()
                .setTransitionViolationValue(reason)
                .setMessage("Workflow transition rejected"))
        .build();
  }

  public Mono<ProjectWorkflow> create(CreateWorkflowRequestBody q) {
    return principal(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_CONFIGURE, null)
        .flatMap(
            principal ->
                registry(principal, null)
                    .flatMap(
                        a -> {
                          var keys =
                              q.getIssueTypeKeysCount() == 0
                                  ? q.getIssueTypesList().stream()
                                      .map(t -> key(false, "", t))
                                      .toList()
                                  : q.getIssueTypeKeysList();
                          requireTypes(a, keys);
                          if (q.getStatusesCount() > 5000 || q.getTransitionsCount() > 10000)
                            return Mono.error(invalid("Workflow graph is too large"));
                          if (!q.getStatusMappingsMap().isEmpty())
                            return Mono.error(migrationRequired());
                          UUID id = UUID.randomUUID();
                          var statuses =
                              q.getStatusesList().stream()
                                  .map(
                                      n ->
                                          new Status(
                                              UUID.randomUUID(),
                                              id,
                                              n.getStatusKey(),
                                              n.getName().strip(),
                                              category(n.getCategory()),
                                              n.getSortOrder()))
                                  .toList();
                          var ids = new HashMap<String, UUID>();
                          statuses.forEach(n -> ids.put(n.key(), n.id()));
                          var edges =
                              q.getTransitionsList().stream()
                                  .map(
                                      t ->
                                          new Transition(
                                              UUID.randomUUID(),
                                              id,
                                              ids.get(t.getFromStatusKey()),
                                              ids.get(t.getToStatusKey()),
                                              t.getName().strip(),
                                              t.getIsHidden(),
                                              t.getSortOrder()))
                                  .toList();
                          validateGraph(a.project(), q.getName().strip(), keys, statuses, edges);
                          return write(
                              a,
                              (s, p) ->
                                  s.creation(
                                          p.project(),
                                          uuid(p.frame().context().actorId()),
                                          p.frame().context().requestId())
                                      .flatMap(
                                          receipt -> {
                                            if (!receipt.hash().equals(hash(q)))
                                              return Mono.error(
                                                  new DomainException(
                                                      DomainStatus.ALREADY_EXISTS,
                                                      "IDEMPOTENCY_CONFLICT"));
                                            return decision(
                                                    principal,
                                                    Action.WORKFLOW_CONFIGURE,
                                                    true,
                                                    receipt.id())
                                                .flatMap(
                                                    d ->
                                                        audit(
                                                            s,
                                                            new Authorized(
                                                                p.frame(),
                                                                p.project(),
                                                                p.types(),
                                                                d)))
                                                .then(
                                                    Mono.fromCallable(
                                                        () ->
                                                            ProjectWorkflow.parseFrom(
                                                                receipt.result())));
                                          })
                                      .switchIfEmpty(
                                          Mono.defer(
                                              () ->
                                                  decision(
                                                          principal,
                                                          Action.WORKFLOW_CONFIGURE,
                                                          true,
                                                          null)
                                                      .flatMap(
                                                          d ->
                                                              audit(
                                                                  s,
                                                                  new Authorized(
                                                                      p.frame(),
                                                                      p.project(),
                                                                      p.types(),
                                                                      d)))
                                                      .then(
                                                          compatibleBindings(
                                                              s,
                                                              p,
                                                              keys,
                                                              statuses.stream()
                                                                  .map(Status::key)
                                                                  .toList()))
                                                      .then(
                                                          s.insertWorkflow(
                                                              id, p.project(), q.getName().strip()))
                                                      .thenMany(
                                                          Flux.fromIterable(statuses)
                                                              .concatMap(n -> s.insertStatus(n)))
                                                      .thenMany(
                                                          Flux.fromIterable(edges)
                                                              .concatMap(
                                                                  t -> s.insertTransition(t)))
                                                      .thenMany(
                                                          Flux.fromIterable(keys)
                                                              .concatMap(
                                                                  code ->
                                                                      s.bind(
                                                                          p.project(),
                                                                          code,
                                                                          generation(p, code),
                                                                          id)))
                                                      .then(s.find(id))
                                                      .flatMap(w -> graph(s, w))
                                                      .flatMap(g -> response(s, p, g))
                                                      .flatMap(
                                                          result ->
                                                              s.recordCreation(
                                                                      p.project(),
                                                                      uuid(
                                                                          p.frame()
                                                                              .context()
                                                                              .actorId()),
                                                                      p.frame()
                                                                          .context()
                                                                          .requestId(),
                                                                      hash(q),
                                                                      result.toByteArray())
                                                                  .thenReturn(result)))));
                        }));
  }

  public Mono<WorkflowResponse> addStatus(AddWorkflowStatusRequestBody q) {
    return authorize(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_CONFIGURE, null, false)
        .flatMap(
            a ->
                write(
                    a,
                    (s, p) ->
                        owned(s, p, uuid(q.getWorkflowId()), true)
                            .flatMap(w -> graph(s, w))
                            .flatMap(
                                g -> {
                                  var n = q.getStatus();
                                  var node =
                                      new Status(
                                          UUID.randomUUID(),
                                          g.workflow().id(),
                                          n.getStatusKey(),
                                          n.getName().strip(),
                                          category(n.getCategory()),
                                          n.getSortOrder());
                                  var all = new ArrayList<>(g.statuses());
                                  all.add(node);
                                  validateGraph(
                                      p.project(),
                                      g.workflow().name(),
                                      List.of("TASK"),
                                      all,
                                      g.transitions());
                                  return s.insertStatus(node)
                                      .then(finish(s, p, g.workflow(), g.workflow().name()))
                                      .map(ProjectWorkflow::getWorkflow);
                                })));
  }

  public Mono<WorkflowResponse> addTransition(AddWorkflowTransitionRequestBody q) {
    return authorize(q.getProjectId(), q.getActorUserId(), Action.WORKFLOW_CONFIGURE, null, false)
        .flatMap(
            a ->
                write(
                    a,
                    (s, p) ->
                        owned(s, p, uuid(q.getWorkflowId()), true)
                            .flatMap(w -> graph(s, w))
                            .flatMap(
                                g -> {
                                  var t = q.getTransition();
                                  var ids = new HashMap<String, UUID>();
                                  g.statuses().forEach(n -> ids.put(n.key(), n.id()));
                                  var edge =
                                      new Transition(
                                          UUID.randomUUID(),
                                          g.workflow().id(),
                                          ids.get(t.getFromStatusKey()),
                                          ids.get(t.getToStatusKey()),
                                          t.getName().strip(),
                                          t.getIsHidden(),
                                          t.getSortOrder());
                                  var all = new ArrayList<>(g.transitions());
                                  all.add(edge);
                                  validateGraph(
                                      p.project(),
                                      g.workflow().name(),
                                      List.of("TASK"),
                                      g.statuses(),
                                      all);
                                  return s.insertTransition(edge)
                                      .then(finish(s, p, g.workflow(), g.workflow().name()))
                                      .map(ProjectWorkflow::getWorkflow);
                                })));
  }

  public Mono<ProjectWorkflow> mutate(Operation operation, WorkflowMutationRequest q) {
    return authorize(
            q.getProjectId(),
            q.getActorUserId(),
            Action.WORKFLOW_CONFIGURE,
            null,
            q.getReplaceIssueTypes() || operation == Operation.DELETE_WORKFLOW)
        .flatMap(
            a ->
                write(
                    a,
                    (s, p) ->
                        owned(s, p, uuid(q.getWorkflowId()), true)
                            .flatMap(
                                w -> {
                                  if (q.hasVersion()
                                      && (q.getVersion() < 1 || q.getVersion() != w.version()))
                                    return Mono.error(changed());
                                  return graph(s, w)
                                      .flatMap(g -> mutateGraph(s, p, g, operation, q));
                                })));
  }

  private Mono<ProjectWorkflow> mutateGraph(
      Session s, Authorized a, Graph g, Operation op, WorkflowMutationRequest q) {
    if (q.getReplaceIssueTypes() && op != Operation.UPDATE_WORKFLOW
        || !q.getReplaceIssueTypes() && q.getIssueTypeKeysCount() > 0)
      return Mono.error(invalid("issueTypes are only valid for a workflow binding replacement"));
    if (!q.getStatusMappingsMap().isEmpty()) return Mono.error(migrationRequired());
    String name = q.hasName() ? q.getName().strip() : g.workflow().name();
    if (op == Operation.UPDATE_WORKFLOW) {
      if (!q.hasName() && !q.getReplaceIssueTypes()) return Mono.error(invalid("name is required"));
      validateGraph(a.project(), name, List.of("TASK"), g.statuses(), g.transitions());
      if (!q.getReplaceIssueTypes()) return finish(s, a, g.workflow(), name);
      if (!q.hasVersion())
        return Mono.error(invalid("version is required for binding replacement"));
      requireTypes(a, q.getIssueTypeKeysList());
      return replaceBindings(s, a, g, q.getIssueTypeKeysList())
          .then(finish(s, a, g.workflow(), name));
    }
    if (op == Operation.DELETE_WORKFLOW) return deleteWorkflow(s, a, g);
    if (op == Operation.UPDATE_STATUS || op == Operation.DELETE_STATUS) {
      UUID id = uuid(q.getStatusId());
      var old =
          g.statuses().stream()
              .filter(n -> n.id().equals(id))
              .findFirst()
              .orElseThrow(TenantWorkflowApi::missing);
      var nodes = new ArrayList<>(g.statuses());
      var edges = new ArrayList<>(g.transitions());
      if (op == Operation.DELETE_STATUS) {
        if (old.key().equals("TODO"))
          return Mono.error(invalid("Initial TODO status cannot be deleted"));
        nodes.remove(old);
        edges.removeIf(t -> t.from().equals(id) || t.to().equals(id));
        validateGraph(a.project(), g.workflow().name(), List.of("TASK"), nodes, edges);
        return s.bindings(List.of(a.project()))
            .any(b -> b.workflow().equals(g.workflow().id()))
            .flatMap(
                bound ->
                    bound
                        ? Mono.error(migrationRequired())
                        : s.deleteStatus(g.workflow().id(), id)
                            .then(finish(s, a, g.workflow(), g.workflow().name())));
      }
      if (!q.hasName() && !q.hasCategory() && !q.hasSortOrder())
        return Mono.error(invalid("At least one status field is required"));
      var changed =
          new Status(
              old.id(),
              old.workflow(),
              old.key(),
              q.hasName() ? q.getName().strip() : old.name(),
              q.hasCategory() ? category(q.getCategory()) : old.category(),
              q.hasSortOrder() ? q.getSortOrder() : old.order());
      nodes.set(nodes.indexOf(old), changed);
      validateGraph(a.project(), g.workflow().name(), List.of("TASK"), nodes, edges);
      return s.updateStatus(changed).then(finish(s, a, g.workflow(), g.workflow().name()));
    }
    UUID id = uuid(q.getTransitionId());
    var old =
        g.transitions().stream()
            .filter(t -> t.id().equals(id))
            .findFirst()
            .orElseThrow(TenantWorkflowApi::missing);
    var edges = new ArrayList<>(g.transitions());
    Mono<Void> update;
    if (op == Operation.DELETE_TRANSITION) {
      edges.remove(old);
      update = s.deleteTransition(g.workflow().id(), id);
    } else {
      if (!q.hasName() && !q.hasHidden() && !q.hasSortOrder())
        return Mono.error(invalid("At least one transition field is required"));
      var changed =
          new Transition(
              old.id(),
              old.workflow(),
              old.from(),
              old.to(),
              q.hasName() ? q.getName().strip() : old.name(),
              q.hasHidden() ? q.getHidden() : old.hidden(),
              q.hasSortOrder() ? q.getSortOrder() : old.order());
      edges.set(edges.indexOf(old), changed);
      update = s.updateTransition(changed);
    }
    validateGraph(a.project(), g.workflow().name(), List.of("TASK"), g.statuses(), edges);
    return update.then(finish(s, a, g.workflow(), g.workflow().name()));
  }

  private Mono<Void> compatibleBindings(
      Session s, Authorized a, List<String> requested, List<String> valid) {
    return Flux.fromIterable(requested)
        .concatMap(
            code ->
                resolve(s, a, code)
                    .flatMap(w -> s.statuses(w.id()).all(n -> valid.contains(n.key())))
                    .flatMap(ok -> ok ? Mono.empty() : Mono.error(migrationRequired())))
        .then();
  }

  private Mono<Void> replaceBindings(Session s, Authorized a, Graph target, List<String> desired) {
    return compatibleBindings(s, a, desired, target.statuses().stream().map(Status::key).toList())
        .then(
            s.bindings(List.of(a.project()))
                .filter(
                    b -> b.workflow().equals(target.workflow().id()) && !desired.contains(b.type()))
                .collectList())
        .flatMap(
            removed -> {
              Mono<Void> restore =
                  removed.isEmpty()
                      ? Mono.empty()
                      : s.freshDefault(a.project())
                          .flatMap(
                              fallback ->
                                  s.statuses(fallback.id())
                                      .map(Status::key)
                                      .collectList()
                                      .flatMap(
                                          valid -> {
                                            if (!valid.containsAll(
                                                target.statuses().stream()
                                                    .map(Status::key)
                                                    .toList()))
                                              return Mono.error(migrationRequired());
                                            return Flux.fromIterable(removed)
                                                .concatMap(
                                                    binding ->
                                                        s.bind(
                                                            a.project(),
                                                            binding.type(),
                                                            generation(a, binding.type()),
                                                            fallback.id()))
                                                .then();
                                          }));
              return restore
                  .thenMany(
                      Flux.fromIterable(desired)
                          .concatMap(
                              code ->
                                  s.bind(
                                      a.project(),
                                      code,
                                      generation(a, code),
                                      target.workflow().id())))
                  .then();
            });
  }

  private Mono<ProjectWorkflow> deleteWorkflow(Session s, Authorized a, Graph graph) {
    return s.bindings(List.of(a.project()))
        .filter(b -> b.workflow().equals(graph.workflow().id()))
        .collectList()
        .flatMap(
            bound -> {
              if (bound.isEmpty())
                return s.deactivate(graph.workflow().id(), graph.workflow().version())
                    .thenReturn(ProjectWorkflow.newBuilder().setWorkflow(graph.response()).build());
              return s.freshDefault(a.project())
                  .flatMap(fallback -> graph(s, fallback))
                  .flatMap(
                      fallback -> {
                        if (!fallback.statuses().stream()
                            .map(Status::key)
                            .toList()
                            .containsAll(graph.statuses().stream().map(Status::key).toList()))
                          return Mono.error(migrationRequired());
                        return Flux.fromIterable(bound)
                            .concatMap(
                                b ->
                                    s.bind(
                                        a.project(),
                                        b.type(),
                                        generation(a, b.type()),
                                        fallback.workflow().id()))
                            .then(s.deactivate(graph.workflow().id(), graph.workflow().version()))
                            .then(response(s, a, fallback));
                      });
            });
  }

  private Mono<ProjectWorkflow> finish(Session s, Authorized a, Workflow w, String name) {
    return s.rename(w.id(), w.version(), name)
        .flatMap(saved -> graph(s, saved))
        .flatMap(g -> response(s, a, g));
  }

  private Mono<Workflow> owned(Session s, Authorized a, UUID workflow, boolean lock) {
    return (lock ? s.lock(workflow) : s.find(workflow))
        .filter(w -> w.active() && w.project().equals(a.project()))
        .switchIfEmpty(Mono.error(missing()));
  }

  private Mono<Workflow> resolve(Session s, Authorized a, String code) {
    type(code);
    return s.resolve(a.project(), code, generation(a, code)).switchIfEmpty(Mono.error(missing()));
  }

  private static UUID generation(Authorized a, String code) {
    return a.types().stream()
        .filter(t -> t.getCode().equals(code))
        .findFirst()
        .map(t -> uuid(t.getGeneration()))
        .orElseThrow(
            () ->
                new DomainException(
                    DomainStatus.INVALID_ARGUMENT,
                    "Issue type is not registered in this project",
                    Map.of("reason", "ISSUE_TYPE_NOT_IN_PROJECT")));
  }

  private static void requireTypes(Authorized a, List<String> requested) {
    if (requested.isEmpty()
        || requested.size() > 5000
        || new HashSet<>(requested).size() != requested.size())
      throw invalid("Invalid issue type list");
    requested.forEach(
        code -> {
          type(code);
          generation(a, code);
        });
  }

  private Mono<Graph> graph(Session s, Workflow w) {
    return s.statuses(w.id())
        .collectList()
        .flatMap(
            nodes ->
                s.transitions(w.id())
                    .collectList()
                    .flatMap(
                        edges ->
                            s.timestamps(w.id())
                                .map(
                                    times -> {
                                      var result =
                                          WorkflowResponse.newBuilder()
                                              .setId(w.id().toString())
                                              .setName(w.name())
                                              .setVersion(w.version())
                                              .setCreatedAt(stamp(times.get(0)))
                                              .setUpdatedAt(stamp(times.get(1)));
                                      nodes.forEach(
                                          n ->
                                              result.addStatuses(
                                                  WorkflowStatus.newBuilder()
                                                      .setId(n.id().toString())
                                                      .setStatusKey(n.key())
                                                      .setName(n.name())
                                                      .setCategory(protoCategory(n.category()))
                                                      .setSortOrder(n.order())));
                                      edges.forEach(
                                          t ->
                                              result.addTransitions(
                                                  WorkflowTransition.newBuilder()
                                                      .setId(t.id().toString())
                                                      .setFromStatusId(t.from().toString())
                                                      .setToStatusId(t.to().toString())
                                                      .setName(t.name())
                                                      .setIsHidden(t.hidden())
                                                      .setSortOrder(t.order())));
                                      return new Graph(w, nodes, edges, result.build());
                                    })));
  }

  private Mono<ProjectWorkflow> response(Session s, Authorized a, Graph graph) {
    return s.bindings(List.of(a.project()))
        .filter(
            b ->
                b.workflow().equals(graph.workflow().id())
                    && a.types().stream()
                        .anyMatch(
                            t ->
                                t.getCode().equals(b.type())
                                    && t.getGeneration().equals(b.generation().toString())))
        .map(Binding::type)
        .collectList()
        .map(
            keys -> {
              var workflow = graph.response().toBuilder().addAllIssueTypeKeys(keys);
              var result = ProjectWorkflow.newBuilder().addAllIssueTypeKeys(keys);
              keys.forEach(
                  code -> {
                    var legacy = legacyType(code);
                    if (legacy != null) {
                      result.addIssueTypes(legacy);
                      workflow.addIssueTypes(legacy);
                    }
                  });
              return result.setWorkflow(workflow).build();
            });
  }

  private void validateGraph(
      UUID project,
      String name,
      List<String> types,
      List<Status> statuses,
      List<Transition> transitions) {
    var keys = new HashMap<UUID, String>();
    statuses.forEach(n -> keys.put(n.id(), n.key()));
    var dto =
        WorkflowCreationDto.builder()
            .projectId(project)
            .name(name)
            .issueTypes(types.stream().map(ru.taska.domain.IssueType::valueOf).toList())
            .statuses(
                statuses.stream()
                    .map(
                        n ->
                            CreateWorkflowStatusDto.builder()
                                .statusKey(n.key())
                                .name(n.name())
                                .category(ru.taska.domain.StatusCategory.valueOf(n.category()))
                                .sortOrder(n.order())
                                .build())
                    .toList())
            .transitions(
                transitions.stream()
                    .map(
                        t ->
                            CreateWorkflowTransitionDto.builder()
                                .fromStatusKey(keys.get(t.from()))
                                .toStatusKey(keys.get(t.to()))
                                .name(t.name())
                                .hidden(t.hidden())
                                .sortOrder(t.order())
                                .build())
                    .toList())
            .build();
    var violations = validator.validateDto(dto);
    if (!violations.isEmpty()) {
      // Domain keys/names can contain private caller text; expose only closed violation codes.
      throw new DomainException(
          DomainStatus.INVALID_ARGUMENT,
          "Invalid workflow configuration",
          Map.of(
              "violations",
              violations.stream()
                  .map(v -> v.type().name())
                  .distinct()
                  .sorted()
                  .collect(java.util.stream.Collectors.joining(","))));
    }
  }

  private static Timestamp stamp(OffsetDateTime time) {
    var instant = time.toInstant();
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static String hash(com.google.protobuf.Message body) {
    try {
      var buffer = new java.io.ByteArrayOutputStream();
      var output = com.google.protobuf.CodedOutputStream.newInstance(buffer);
      output.useDeterministicSerialization();
      body.writeTo(output);
      output.flush();
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Workflow command serialization failed", e);
    }
  }

  static UUID uuid(String text) {
    try {
      UUID id = UUID.fromString(text);
      if (!id.toString().equals(text) || id.equals(new UUID(0, 0)))
        throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw invalid("A nonzero canonical UUID is required");
    }
  }

  private static void type(String code) {
    if (!code.matches("^[A-Z][A-Z0-9_]{1,31}$")) throw invalid("Invalid issue type key");
  }

  private static void statusKey(String key) {
    if (!key.matches("^[A-Z][A-Z0-9_]{1,63}$")) throw invalid("Invalid status key");
  }

  private static String key(
      boolean present, String key, ru.taska.api.workflow.v1.IssueType legacy) {
    if (present) {
      type(key);
      return key;
    }
    return switch (legacy) {
      case ISSUE_TYPE_TASK -> "TASK";
      case ISSUE_TYPE_BUG -> "BUG";
      case ISSUE_TYPE_STORY -> "STORY";
      default -> throw invalid("An issue type key is required");
    };
  }

  private static ru.taska.api.workflow.v1.IssueType legacyType(String code) {
    return switch (code) {
      case "TASK" -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK;
      case "BUG" -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_BUG;
      case "STORY" -> ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_STORY;
      default -> null;
    };
  }

  private static String category(ru.taska.api.workflow.v1.StatusCategory category) {
    return switch (category) {
      case STATUS_CATEGORY_TODO -> "TODO";
      case STATUS_CATEGORY_IN_PROGRESS -> "IN_PROGRESS";
      case STATUS_CATEGORY_DONE -> "DONE";
      default -> throw invalid("Invalid status category");
    };
  }

  private static ru.taska.api.workflow.v1.StatusCategory protoCategory(String category) {
    return ru.taska.api.workflow.v1.StatusCategory.valueOf("STATUS_CATEGORY_" + category);
  }

  private static DomainException invalid(String message) {
    return new DomainException(DomainStatus.INVALID_ARGUMENT, message);
  }

  private static DomainException missing() {
    return new DomainException(DomainStatus.NOT_FOUND, "Workflow resource not found in project");
  }

  private static DomainException denied() {
    return new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED");
  }

  private static DomainException changed() {
    return new DomainException(
        DomainStatus.ABORTED,
        "Workflow binding or version changed",
        Map.of("reason", "WORKFLOW_CHANGED"));
  }

  private static DomainException pending() {
    return new DomainException(
        DomainStatus.ABORTED,
        "Project issue-type operation pending",
        Map.of("reason", "ISSUE_TYPE_OPERATION_PENDING"));
  }

  private static DomainException migrationRequired() {
    return new DomainException(
        DomainStatus.UNAVAILABLE,
        "Native issue migration authority is required",
        Map.of("reason", "WORKFLOW_MIGRATION_UNAVAILABLE"));
  }

  private static DomainException unavailable(String message) {
    return new DomainException(DomainStatus.UNAVAILABLE, message);
  }
}

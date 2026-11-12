package ru.taska.workflowstorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.taska.security.tenant.WorkflowTestContext.as;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import reactor.core.publisher.*;
import ru.taska.api.b2b.v1.Capability;
import ru.taska.api.project.v1.*;
import ru.taska.api.workflow.v1.*;
import ru.taska.capability.*;
import ru.taska.exception.*;
import ru.taska.security.tenant.VerifiedTenantContext;
import ru.taska.service.WorkflowMutationService.Operation;

/** Real app LOGIN/RLS and shipped migrations. Primary boundary doubles are covered by owned E2E. */
class TenantWorkflowApiIntegrationTest extends TenantWorkflowFixture {
  final UUID project = UUID.randomUUID(), otherProject = UUID.randomUUID();
  final UUID generation = UUID.randomUUID();
  TenantWorkflowApi api;
  TenantDecisionFacts facts;
  WorkflowProjectAccess access;
  TenantWorkflowStore.Workflow workflow;
  final AtomicReference<ProjectRole> role = new AtomicReference<>(ProjectRole.PROJECT_ROLE_ADMIN);

  @BeforeEach
  void prepareApi() {
    facts = mock(TenantDecisionFacts.class);
    access = mock(WorkflowProjectAccess.class);
    when(facts.current(anyString()))
        .thenAnswer(
            call ->
                VerifiedTenantContext.current()
                    .map(
                        c ->
                            new TenantDecisionFacts.Frame(
                                c,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                Instant.now().plusSeconds(30))));
    when(facts.decision(any(), any(), any(), any(), any()))
        .thenAnswer(
            call -> {
              var actual = call.getArgument(3, CapabilityFacts.ProjectRole.class);
              if (call.getArgument(1) != Action.WORKFLOW_READ
                  && actual != CapabilityFacts.ProjectRole.ADMIN
                  && call.getArgument(1) != Action.ISSUE_TRANSITION)
                return Mono.error(
                    new DomainException(DomainStatus.PERMISSION_DENIED, "SCOPE_DENIED"));
              return Mono.just(
                  new CapabilityEvaluator.Decision(
                      Capability.newBuilder().setAllowed(true).build(), false, false, Set.of()));
            });
    when(access.check(any()))
        .thenAnswer(
            call ->
                call.getArgument(0).equals(project)
                    ? Mono.just(
                        CheckProjectMemberRoleResponse.newBuilder()
                            .setProjectExists(true)
                            .setIsMember(true)
                            .setRole(role.get())
                            .setProjectArchived(false)
                            .build())
                    : Mono.error(new DomainException(DomainStatus.NOT_FOUND, "Project not found")));
    when(access.types(project))
        .thenReturn(
            Mono.just(
                ListProjectIssueTypesResponse.newBuilder()
                    .addItems(
                        ProjectIssueTypeResponse.newBuilder()
                            .setCode("TASK")
                            .setGeneration(generation.toString()))
                    .build()));
    api = new TenantWorkflowApi(store, facts, access, json);
    workflow = graph(A, project);
    as(A, ACTOR, store.execute(s -> s.bind(project, "TASK", generation, workflow.id())))
        .block(WAIT);
  }

  WorkflowMutationRequest.Builder command() {
    return WorkflowMutationRequest.newBuilder()
        .setProjectId(project.toString())
        .setActorUserId(ACTOR.toString())
        .setWorkflowId(workflow.id().toString());
  }

  WorkflowResponse get() {
    return as(
            A,
            ACTOR,
            api.get(
                GetWorkflowForProjectRequestBody.newBuilder()
                    .setProjectId(project.toString())
                    .setActorUserId(ACTOR.toString())
                    .setIssueTypeKey("TASK")
                    .build()))
        .block(WAIT);
  }

  DomainException reject(Mono<?> work, DomainStatus status) {
    var error = assertThrows(DomainException.class, () -> as(A, ACTOR, work).block(WAIT));
    assertEquals(status, error.getStatus());
    return error;
  }

  Mono<ru.taska.api.workflow.v1.ProjectWorkflow> mutate(
      Operation operation, WorkflowMutationRequest.Builder q) {
    return api.mutate(operation, q.build());
  }

  @Test
  void rejectsForeignWorkflowAndNestedIdsWithoutChangingEitherGraph() {
    var foreign = graph(B, project);
    var sameTenant = graph(A, otherProject);
    for (var graph : List.of(foreign, sameTenant)) {
      reject(
          mutate(
              Operation.UPDATE_WORKFLOW,
              command().setWorkflowId(graph.id().toString()).setName("Denied")),
          DomainStatus.NOT_FOUND);
      var foreignStatus =
          as(graph == foreign ? B : A, ACTOR, store.execute(s -> s.statuses(graph.id()).next()))
              .block(WAIT);
      reject(
          mutate(
              Operation.UPDATE_STATUS,
              command().setStatusId(foreignStatus.id().toString()).setName("Denied")),
          DomainStatus.NOT_FOUND);
      var foreignTransition =
          as(graph == foreign ? B : A, ACTOR, store.execute(s -> s.transitions(graph.id()).next()))
              .block(WAIT);
      reject(
          mutate(
              Operation.DELETE_TRANSITION,
              command().setTransitionId(foreignTransition.id().toString())),
          DomainStatus.NOT_FOUND);
    }
    assertEquals(1, get().getVersion());
    assertEquals(3, get().getStatusesCount());
    assertEquals(
        "Default workflow",
        as(B, ACTOR, store.execute(s -> s.find(foreign.id()))).block(WAIT).name());
  }

  @Test
  void authorityFailureAndPayloadActorSubstitutionPreserveState() {
    reject(
        mutate(
            Operation.UPDATE_WORKFLOW,
            command().setActorUserId(UUID.randomUUID().toString()).setName("Denied")),
        DomainStatus.PERMISSION_DENIED);
    reject(
        mutate(
            Operation.UPDATE_WORKFLOW,
            command().setProjectId(otherProject.toString()).setName("Denied")),
        DomainStatus.NOT_FOUND);
    role.set(ProjectRole.PROJECT_ROLE_VIEWER);
    reject(
        mutate(Operation.UPDATE_WORKFLOW, command().setName("Denied")),
        DomainStatus.PERMISSION_DENIED);
    role.set(ProjectRole.PROJECT_ROLE_ADMIN);
    when(access.check(project))
        .thenReturn(
            Mono.error(new DomainException(DomainStatus.UNAVAILABLE, "Primary unavailable")));
    reject(
        mutate(Operation.UPDATE_WORKFLOW, command().setName("Denied")), DomainStatus.UNAVAILABLE);
    assertEquals(1, as(A, ACTOR, store.execute(s -> s.find(workflow.id()))).block(WAIT).version());
  }

  @Test
  void concurrentVersionedSavesHaveOneWinnerAndNoPartialLoser() {
    var results =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(
                            A,
                            ACTOR,
                            mutate(
                                Operation.UPDATE_WORKFLOW,
                                command().setVersion(1).setName("Winner " + i)))
                        .map(x -> "OK")
                        .onErrorResume(DomainException.class, e -> Mono.just(e.getStatus().name())))
            .collectList()
            .block(WAIT);
    assertEquals(1, Collections.frequency(results, "OK"));
    assertEquals(1, Collections.frequency(results, "ABORTED"));
    assertEquals(2, get().getVersion());
    assertTrue(get().getName().startsWith("Winner "));
  }

  @Test
  void graphValidationRollsBackAndMetadataKeepsDoneCategory() {
    var before = get();
    var status = before.getStatuses(1);
    reject(
        mutate(
            Operation.UPDATE_STATUS,
            command()
                .setStatusId(status.getId())
                .setSortOrder(before.getStatuses(0).getSortOrder())),
        DomainStatus.INVALID_ARGUMENT);
    assertEquals(before, get());
    var updated =
        as(
                A,
                ACTOR,
                mutate(
                    Operation.UPDATE_STATUS,
                    command()
                        .setVersion(1)
                        .setStatusId(status.getId())
                        .setCategory(StatusCategory.STATUS_CATEGORY_DONE)
                        .setName("Finished")))
            .block(WAIT);
    assertEquals(2, updated.getWorkflow().getVersion());
    assertEquals(
        StatusCategory.STATUS_CATEGORY_DONE,
        get().getStatusesList().stream()
            .filter(n -> n.getId().equals(status.getId()))
            .findFirst()
            .orElseThrow()
            .getCategory());
  }

  @Test
  void scopeRejectsWrongGenerationWorkflowAndInitialStatus() {
    var q =
        CheckProjectWorkflowScopeRequest.newBuilder()
            .setProjectId(project.toString())
            .setActorUserId(ACTOR.toString())
            .addIssueTypeKeys("TASK");
    as(
            A,
            ACTOR,
            api.scope(
                q.setExpectedWorkflowId(workflow.id().toString())
                    .setExpectedWorkflowVersion(1)
                    .build()))
        .block(WAIT);
    reject(api.scope(q.setExpectedWorkflowVersion(2).build()), DomainStatus.ABORTED);
    reject(
        api.scope(q.setExpectedWorkflowVersion(1).setStatusKey("UNKNOWN").build()),
        DomainStatus.FAILED_PRECONDITION);
    when(access.types(project))
        .thenReturn(
            Mono.just(
                ListProjectIssueTypesResponse.newBuilder()
                    .addItems(
                        ProjectIssueTypeResponse.newBuilder()
                            .setCode("TASK")
                            .setGeneration(UUID.randomUUID().toString()))
                    .build()));
    reject(api.scope(q.clearStatusKey().build()), DomainStatus.NOT_FOUND);
  }

  @Test
  void runtimeTransitionChecksCurrentAndForeignEdgesWithoutMutation() {
    var graph = get();
    var transition = graph.getTransitions(0);
    var source =
        graph.getStatusesList().stream()
            .filter(n -> n.getId().equals(transition.getFromStatusId()))
            .findFirst()
            .orElseThrow();
    var snapshot =
        IssueValidateSnapshot.newBuilder()
            .setProjectId(project.toString())
            .setIssueId(UUID.randomUUID().toString())
            .setIssueTypeKey("TASK")
            .setStatusKey(source.getStatusKey());
    var q =
        ValidateTransitionRequestBody.newBuilder()
            .setIssueSnapshot(snapshot)
            .setActorUserId(ACTOR.toString())
            .setTransitionId(transition.getId());
    assertTrue(as(A, ACTOR, api.validate(q.build())).block(WAIT).getIsValid());
    assertFalse(
        as(A, ACTOR, api.validate(q.setTransitionId(UUID.randomUUID().toString()).build()))
            .block(WAIT)
            .getIsValid());
    assertEquals(graph, get());
  }

  @Test
  void additionsAndHiddenTransitionsRemainScopedAndAtomic() {
    as(
            A,
            ACTOR,
            api.addStatus(
                AddWorkflowStatusRequestBody.newBuilder()
                    .setProjectId(project.toString())
                    .setActorUserId(ACTOR.toString())
                    .setWorkflowId(workflow.id().toString())
                    .setStatus(
                        WorkflowStatusCreateRequest.newBuilder()
                            .setStatusKey("QA")
                            .setName("QA")
                            .setCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                            .setSortOrder(99))
                    .build()))
        .block(WAIT);
    var added =
        as(
                A,
                ACTOR,
                api.addTransition(
                    AddWorkflowTransitionRequestBody.newBuilder()
                        .setProjectId(project.toString())
                        .setActorUserId(ACTOR.toString())
                        .setWorkflowId(workflow.id().toString())
                        .setTransition(
                            WorkflowTransitionCreateRequest.newBuilder()
                                .setFromStatusKey("TODO")
                                .setToStatusKey("QA")
                                .setName("Review")
                                .setIsHidden(true)
                                .setSortOrder(99))
                        .build()))
            .block(WAIT);
    assertEquals(3, added.getVersion());
    assertEquals(7, added.getTransitionsCount());
    assertEquals(6, get().getTransitionsCount());
    var list =
        as(
                A,
                ACTOR,
                api.list(
                    ListProjectWorkflowsRequestBody.newBuilder()
                        .setProjectId(project.toString())
                        .setActorUserId(ACTOR.toString())
                        .build()))
            .block(WAIT);
    assertEquals(7, list.getWorkflows(0).getWorkflow().getTransitionsCount());
    assertEquals(workflow.id().toString(), list.getMainWorkflowId());
  }

  @Test
  void pendingPinsRejectWritesWithoutChangingGraph() {
    var before = get();
    as(A, ACTOR, store.execute(s -> s.pin(project, UUID.randomUUID(), workflow.id()))).block(WAIT);
    reject(mutate(Operation.UPDATE_WORKFLOW, command().setName("Denied")), DomainStatus.ABORTED);
    assertEquals(before, get());
  }

  @Test
  void createKeepsBindingsAtomicAndDeletingRestoresAPrivateTemplateGraph() {
    var initial = get();
    var keys = new HashMap<String, String>();
    initial.getStatusesList().forEach(n -> keys.put(n.getId(), n.getStatusKey()));
    var q =
        CreateWorkflowRequestBody.newBuilder()
            .setProjectId(project.toString())
            .setActorUserId(ACTOR.toString())
            .setName("Custom graph")
            .addIssueTypeKeys("TASK");
    initial
        .getStatusesList()
        .forEach(
            n ->
                q.addStatuses(
                    WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey(n.getStatusKey())
                        .setName(n.getName())
                        .setCategory(n.getCategory())
                        .setSortOrder(n.getSortOrder())));
    initial
        .getTransitionsList()
        .forEach(
            t ->
                q.addTransitions(
                    WorkflowTransitionCreateRequest.newBuilder()
                        .setFromStatusKey(keys.get(t.getFromStatusId()))
                        .setToStatusKey(keys.get(t.getToStatusId()))
                        .setName(t.getName())
                        .setIsHidden(t.getIsHidden())
                        .setSortOrder(t.getSortOrder())));
    var created = as(A, ACTOR, api.create(q.build())).block(WAIT).getWorkflow();
    assertEquals(created.getId(), get().getId());
    var restored =
        as(
                A,
                ACTOR,
                mutate(
                    Operation.DELETE_WORKFLOW,
                    command().setWorkflowId(created.getId()).setVersion(created.getVersion())))
            .block(WAIT)
            .getWorkflow();
    assertNotEquals(created.getId(), restored.getId());
    assertNotEquals(initial.getId(), restored.getId());
    assertEquals("Default workflow", restored.getName());
    assertEquals(restored.getId(), get().getId());
    assertFalse(
        as(A, ACTOR, store.execute(s -> s.find(UUID.fromString(created.getId()))))
            .block(WAIT)
            .active());
    assertEquals(3, count("workflows"));
  }

  @Test
  void migrationsFailClosedAndRollbackAnyFallbackAllocation() {
    var added =
        as(
                A,
                ACTOR,
                api.addStatus(
                    AddWorkflowStatusRequestBody.newBuilder()
                        .setProjectId(project.toString())
                        .setActorUserId(ACTOR.toString())
                        .setWorkflowId(workflow.id().toString())
                        .setStatus(
                            WorkflowStatusCreateRequest.newBuilder()
                                .setStatusKey("QA")
                                .setName("QA")
                                .setCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                                .setSortOrder(99))
                        .build()))
            .block(WAIT);
    var qa =
        added.getStatusesList().stream()
            .filter(n -> n.getStatusKey().equals("QA"))
            .findFirst()
            .orElseThrow();
    var before = get();
    long graphs = count("workflows"), nodes = count("statuses"), edges = count("transitions");
    reject(
        mutate(
            Operation.DELETE_STATUS, command().setStatusId(qa.getId()).setTargetStatusKey("TODO")),
        DomainStatus.UNAVAILABLE);
    reject(mutate(Operation.DELETE_WORKFLOW, command()), DomainStatus.UNAVAILABLE);
    assertEquals(graphs, count("workflows"));
    assertEquals(nodes, count("statuses"));
    assertEquals(edges, count("transitions"));
    assertEquals(before, get());
  }

  @Test
  void concurrentAdditionsSerializeWithoutLostNodesOrEdges() {
    var statuses =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(
                        A,
                        ACTOR,
                        api.addStatus(
                            AddWorkflowStatusRequestBody.newBuilder()
                                .setProjectId(project.toString())
                                .setActorUserId(ACTOR.toString())
                                .setWorkflowId(workflow.id().toString())
                                .setStatus(
                                    WorkflowStatusCreateRequest.newBuilder()
                                        .setStatusKey("QA" + i)
                                        .setName("QA")
                                        .setCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                                        .setSortOrder(90 + i))
                                .build())))
            .collectList()
            .block(WAIT);
    assertEquals(
        Set.of(2, 3),
        statuses.stream()
            .map(WorkflowResponse::getVersion)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(5, get().getStatusesCount());
    var transitions =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(
                        A,
                        ACTOR,
                        api.addTransition(
                            AddWorkflowTransitionRequestBody.newBuilder()
                                .setProjectId(project.toString())
                                .setActorUserId(ACTOR.toString())
                                .setWorkflowId(workflow.id().toString())
                                .setTransition(
                                    WorkflowTransitionCreateRequest.newBuilder()
                                        .setFromStatusKey("TODO")
                                        .setToStatusKey("QA" + i)
                                        .setName("Review")
                                        .setSortOrder(90 + i))
                                .build())))
            .collectList()
            .block(WAIT);
    assertEquals(
        Set.of(4, 5),
        transitions.stream()
            .map(WorkflowResponse::getVersion)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(8, get().getTransitionsCount());
    assertEquals(5, get().getVersion());
  }

  @Test
  void concurrentDuplicateAdditionHasOneWinnerAndNoLoserVersionOrNode() {
    var q =
        AddWorkflowStatusRequestBody.newBuilder()
            .setProjectId(project.toString())
            .setActorUserId(ACTOR.toString())
            .setWorkflowId(workflow.id().toString())
            .setStatus(
                WorkflowStatusCreateRequest.newBuilder()
                    .setStatusKey("QA")
                    .setName("QA")
                    .setCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                    .setSortOrder(90))
            .build();
    var outcomes =
        Flux.range(0, 2)
            .flatMap(
                i ->
                    as(A, ACTOR, api.addStatus(q))
                        .map(x -> "OK")
                        .onErrorResume(DomainException.class, e -> Mono.just(e.getStatus().name())))
            .collectList()
            .block(WAIT);
    assertEquals(1, Collections.frequency(outcomes, "OK"));
    assertEquals(1, Collections.frequency(outcomes, "INVALID_ARGUMENT"));
    assertEquals(4, get().getStatusesCount());
    assertEquals(2, get().getVersion());
  }

  @Test
  void upgradeFromPreviousNativeSchemaIsAtomicAndNeverDeduplicatesGraphData() throws Exception {
    var source =
        java.nio.file.Path.of(getClass().getResource("/db/workflow-tenant/changelog.json").toURI());
    var folder = java.nio.file.Files.createTempDirectory("tas342-upgrade-");
    try {
      try (var files = java.nio.file.Files.list(source.getParent())) {
        for (var file : files.filter(java.nio.file.Files::isRegularFile).toList())
          java.nio.file.Files.copy(file, folder.resolve(file.getFileName()));
      }
      var previous =
          (tools.jackson.databind.node.ObjectNode)
              json.readTree(java.nio.file.Files.readString(source));
      var sets = (tools.jackson.databind.node.ArrayNode) previous.get("databaseChangeLog");
      sets.remove(sets.size() - 1);
      for (var entry : sets)
        ((tools.jackson.databind.node.ObjectNode) entry.get("changeSet"))
            .put("logicalFilePath", "db/workflow-tenant/changelog.json");
      var oldMaster = folder.resolve("changelog.json");
      java.nio.file.Files.writeString(oldMaster, json.writeValueAsString(previous));
      for (String variant : List.of("valid", "statuses", "transitions")) {
        String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + database);
        String url =
            "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
        try {
          bootstrap(database);
          try (var connection =
                  java.sql.DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
              var statement = connection.createStatement()) {
            statement.execute(
                "CREATE SCHEMA workflow_liquibase AUTHORIZATION taska_workflow_migrator;REVOKE ALL"
                    + " ON SCHEMA workflow_liquibase FROM PUBLIC");
          }
          var old =
              new ru.taska.workflowbootstrap.TenantStorageConfiguration()
                  .workflowTenantLiquibase(url, "taska_workflow_deployer", DEPLOY);
          old.setChangeLog(oldMaster.toUri().toString());
          old.afterPropertiesSet();
          String graph = UUID.randomUUID().toString(), p = UUID.randomUUID().toString();
          try (var connection =
                  java.sql.DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
              var statement = connection.createStatement()) {
            statement.execute(
                "INSERT INTO taska.workflows(tenant_id,id,owner_project_id,name) VALUES('"
                    + A
                    + "','"
                    + graph
                    + "','"
                    + p
                    + "','Upgrade graph');INSERT INTO"
                    + " taska.statuses(tenant_id,workflow_id,status_key,name,category,sort_order)"
                    + " VALUES('"
                    + A
                    + "','"
                    + graph
                    + "','TODO','Todo','TODO',10),('"
                    + A
                    + "','"
                    + graph
                    + "','DONE','Done','DONE',20);INSERT INTO"
                    + " taska.transitions(tenant_id,workflow_id,from_status_id,to_status_id,name,sort_order)"
                    + " SELECT '"
                    + A
                    + "','"
                    + graph
                    + "',a.id,b.id,'Finish',10 FROM taska.statuses a,taska.statuses b WHERE"
                    + " a.status_key='TODO' AND b.status_key='DONE';INSERT INTO"
                    + " taska.transitions(tenant_id,workflow_id,from_status_id,to_status_id,name,sort_order)"
                    + " SELECT '"
                    + A
                    + "','"
                    + graph
                    + "',a.id,b.id,'Reopen',20 FROM taska.statuses a,taska.statuses b WHERE"
                    + " a.status_key='DONE' AND b.status_key='TODO';");
            if (!variant.equals("valid"))
              statement.execute("UPDATE taska." + variant + " SET sort_order=10");
            String before = upgradeSnapshot(statement);
            if (variant.equals("valid")) {
              migrate(url);
              migrate(url);
            } else {
              var failure = assertThrows(Exception.class, () -> migrate(url));
              boolean duplicate = false;
              for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState()))
                  duplicate = true;
              assertTrue(
                  duplicate,
                  "Upgrade must reject duplicate orders, not fail on another prerequisite");
            }
            assertEquals(before, upgradeSnapshot(statement));
            try (var rows =
                statement.executeQuery(
                    "SELECT to_regclass('taska.workflow_creation_receipts') IS NOT NULL")) {
              assertTrue(rows.next());
              assertEquals(variant.equals("valid"), rows.getBoolean(1));
            }
          }
        } finally {
          sql("DROP DATABASE " + database + " WITH (FORCE)");
        }
      }
    } finally {
      try (var files = java.nio.file.Files.walk(folder)) {
        for (var file : files.sorted(Comparator.reverseOrder()).toList())
          java.nio.file.Files.deleteIfExists(file);
      }
    }
  }

  private String upgradeSnapshot(java.sql.Statement statement) throws Exception {
    try (var rows =
        statement.executeQuery(
            "SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(w) ORDER BY id) FROM"
                + " taska.workflows w),(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM"
                + " taska.statuses s),(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM"
                + " taska.transitions t))::text")) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }
}

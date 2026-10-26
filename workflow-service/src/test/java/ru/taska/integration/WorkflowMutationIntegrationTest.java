package ru.taska.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.*;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.service.WorkflowCreateService;
import ru.taska.service.WorkflowMutationService;
import ru.taska.service.WorkflowMutationService.Operation;
import ru.taska.transport.grpc.issue.WorkflowIssueMigrationClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.grpc.server.port=0", "spring.grpc.server.host=127.0.0.1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkflowMutationIntegrationTest extends AbstractIT {
    @Autowired WorkflowCreateService creator;
    @Autowired WorkflowMutationService service;
    @Autowired ru.taska.service.WorkflowEditService edits;
    @Autowired ru.taska.service.WorkflowResolver resolver;
    @Autowired ru.taska.service.ProjectWorkflowListService listing;
    @MockitoBean ProjectRoleChecker roles;
    @MockitoBean WorkflowIssueMigrationClient issues;
    private UUID project, actor, workflow, review;

    @MockitoBean
    private ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;

    @BeforeEach
    void createGraph() {
        org.mockito.Mockito.when(projectClient.listIssueTypes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(java.util.List.of(ru.taska.domain.IssueType.values())));
        org.mockito.Mockito.when(projectClient.listIssueTypeDetails(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(ru.taska.RegistryFixtures.types(inv.getArgument(2), ru.taska.domain.IssueType.values())));
        org.mockito.Mockito.when(projectClient.getIssueTypesInternal(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(ru.taska.RegistryFixtures.types(inv.getArgument(0), ru.taska.domain.IssueType.values())));


        project = UUID.randomUUID(); actor = UUID.randomUUID();
        when(roles.checkProjectWriteRole(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(issues.migrate(any(), any(), any(), any())).thenReturn(Mono.just(0L));
        when(issues.migrate(any(), any(), any(), any(), any())).thenReturn(Mono.just(0L));
        var created = creator.validateAndCreateWorkflow("r", "n", actor, WorkflowCreationDto.builder()
                .projectId(project).name("Test").issueTypes(List.of(IssueType.TASK))
                .statuses(List.of(status("TODO", 10), status("REVIEW", 20), status("DONE", 30)))
                .transitions(List.of(edge("TODO", "REVIEW", 10), edge("REVIEW", "DONE", 20), edge("TODO", "DONE", 30)))
                .build()).block();
        workflow = created.workflow().getId();
        review = created.statuses().stream().filter(s -> s.getStatusKey().equals("REVIEW")).findFirst().orElseThrow().getId();
    }

    @Test
    void concurrentEditsWithSameVersionProduceOneSuccessAndOneConflict() {
        var results = Flux.merge(service.mutate(Operation.UPDATE_WORKFLOW, command("One", null, null)).materialize(),
                service.mutate(Operation.UPDATE_WORKFLOW, command("Two", null, null)).materialize()).collectList().block();
        assertThat(results.stream().filter(signal -> signal.isOnNext())).hasSize(1);
        assertThat(results.stream().filter(signal -> signal.isOnError()).map(signal -> signal.getThrowable()).toList())
                .singleElement().satisfies(e -> assertThat(((DomainException) e).getStatus()).isEqualTo(DomainStatus.ABORTED));
        assertThat(databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("version", Integer.class)).one().block()).isEqualTo(2);
    }

    @Test
    void deletionRemovesIncidentEdgesAndBumpsVersion() {
        var changed = service.mutate(Operation.DELETE_STATUS, command(null, review, "DONE")).block();
        assertThat(changed.workflow().workflow().getVersion()).isEqualTo(2);
        assertThat(changed.workflow().statuses()).extracting(s -> s.getStatusKey()).containsExactly("TODO", "DONE");
        assertThat(changed.workflow().transitions()).hasSize(1);
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.transitions WHERE from_status_id=:id OR to_status_id=:id")
                .bind("id", review).map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
    }

    @Test
    void deletingOwnWorkflowFallsBackToDefaultAndDropsOnlyItsGraph() {
        var fallback = service.mutate(Operation.DELETE_WORKFLOW, command(null, null, null)).block();
        assertThat(fallback.shared()).isTrue();
        assertThat(fallback.workflow().workflow().getId()).isNotEqualTo(workflow);
        assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
    }

    @Test
    void concurrentAdditionAndDeletionSerializeAndPreserveBothChanges() {
        var results = Flux.merge(
                edits.addStatus("r", "n", actor, project, workflow, status("CODE_REVIEW", 25)).then().materialize(),
                service.mutate(Operation.DELETE_STATUS, new WorkflowMutation("r", "n", actor, project, workflow,
                        null, review, null, null, null, null, null, "DONE", Map.of())).then().materialize())
                .collectList().block();
        assertThat(results.stream().filter(signal -> signal.isOnError()).toList()).isEmpty();
        assertThat(databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("version", Integer.class)).one().block()).isEqualTo(3);
        assertThat(databaseClient.sql("SELECT status_key FROM taska.statuses WHERE workflow_id=:id ORDER BY sort_order")
                .bind("id", workflow).map((r, m) -> r.get("status_key", String.class)).all().collectList().block())
                .containsExactly("TODO", "CODE_REVIEW", "DONE");
    }

    @Test
    void archivedProjectCannotRenameOrDeleteWorkflow() {
        when(roles.checkProjectWriteRole(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(ru.taska.exception.ProjectArchived.error()));
        for (var operation : List.of(Operation.UPDATE_WORKFLOW, Operation.DELETE_WORKFLOW)) {
            reactor.test.StepVerifier.create(service.mutate(operation, command("Changed", null, null)))
                    .expectErrorSatisfies(error -> assertThat(((DomainException) error).getDetails())
                            .containsEntry("reason", "PROJECT_ARCHIVED")).verify();
        }
        assertThat(databaseClient.sql("SELECT name FROM taska.workflows WHERE id=:id").bind("id", workflow)
                .map((r, m) -> r.get("name", String.class)).one().block()).isEqualTo("Test");
    }

    @Test
    void statusDeletionCanRetrySameVersionAfterExternalMigrationAndLocalFailure() {
        var migrated = new java.util.concurrent.atomic.AtomicBoolean();
        var movedCount = new java.util.concurrent.atomic.AtomicInteger();
        when(issues.migrate(any(), any(), any(), any(), any())).thenAnswer(call -> Mono.fromSupplier(() -> {
            if (!migrated.getAndSet(true)) { movedCount.incrementAndGet(); return 1L; }
            return 0L;
        }));
        databaseClient.sql("ALTER TABLE taska.workflows ADD CONSTRAINT mutation_retry_test CHECK (id <> '" + workflow + "'::uuid OR version=1)")
                .fetch().rowsUpdated().block();
        try {
            reactor.test.StepVerifier.create(service.mutate(Operation.DELETE_STATUS, command(null, review, "DONE")))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:id").bind("id", workflow)
                    .map((r, m) -> r.get("version", Integer.class)).one().block()).isEqualTo(1);
            assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.statuses WHERE id=:id").bind("id", review)
                    .map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(1);
        } finally {
            databaseClient.sql("ALTER TABLE taska.workflows DROP CONSTRAINT mutation_retry_test").fetch().rowsUpdated().block();
        }
        var result = service.mutate(Operation.DELETE_STATUS, command(null, review, "DONE")).block();
        assertThat(result.workflow().workflow().getVersion()).isEqualTo(2);
        assertThat(result.workflow().statuses()).noneMatch(status -> status.getId().equals(review));
        assertThat(movedCount.get()).isEqualTo(1);
        verify(issues, times(2)).migrate(any(), any(), any(), any(), any());
    }

    @Test
    void workflowDeletionCanRetrySameVersionAfterExternalMigrationAndLocalFailure() {
        var migrated = new java.util.concurrent.atomic.AtomicBoolean();
        var movedCount = new java.util.concurrent.atomic.AtomicInteger();
        when(issues.migrate(any(), any(), any(), any())).thenAnswer(call -> Mono.fromSupplier(() -> {
            if (!migrated.getAndSet(true)) { movedCount.incrementAndGet(); return 1L; }
            return 0L;
        }));
        databaseClient.sql("CREATE TABLE taska.workflow_retry_test (workflow_id uuid REFERENCES taska.workflows(id))")
                .fetch().rowsUpdated().block();
        databaseClient.sql("INSERT INTO taska.workflow_retry_test VALUES (:id)").bind("id", workflow).fetch().rowsUpdated().block();
        try {
            reactor.test.StepVerifier.create(service.mutate(Operation.DELETE_WORKFLOW, command(null, null, null)))
                    .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
            assertThat(databaseClient.sql("SELECT version FROM taska.workflows WHERE id=:id").bind("id", workflow)
                    .map((r, m) -> r.get("version", Integer.class)).one().block()).isEqualTo(1);
            assertThat(databaseClient.sql("SELECT count(*) AS n FROM taska.workflow_bindings WHERE workflow_id=:id")
                    .bind("id", workflow).map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(1);
        } finally {
            databaseClient.sql("DROP TABLE taska.workflow_retry_test").fetch().rowsUpdated().block();
        }
        assertThat(service.mutate(Operation.DELETE_WORKFLOW, command(null, null, null)).block().shared()).isTrue();
        assertThat(movedCount.get()).isEqualTo(1);
        verify(issues, times(2)).migrate(any(), any(), any(), any());
    }

    @Test
    void customTypeUsesDefaultAndCanCreateEditListAndDeleteOwnWorkflow() {
        var request = IssueType.valueOf("REQUEST");
        when(projectClient.listIssueTypes(any(), any(), any(), any())).thenReturn(Mono.just(List.of(IssueType.TASK, request)));
        when(projectClient.listIssueTypeDetails(any(), any(), any(), any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(project, IssueType.TASK, request)));
        when(projectClient.getIssueTypesInternal(any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(project, IssueType.TASK, request)));
        var fallback = resolver.resolveWorkflow(project, "REQUEST").block();
        assertThat(fallback).isNotNull();
        var own = creator.validateAndCreateWorkflow("r", "n", actor, WorkflowCreationDto.builder()
                .projectId(project).name("Requests").issueTypes(List.of(request))
                .statuses(List.of(status("TODO", 10), status("DONE", 20)))
                .transitions(List.of(edge("TODO", "DONE", 10))).build()).block();
        UUID custom = own.workflow().getId();
        assertThat(resolver.resolveWorkflow(project, "REQUEST").block().getId()).isEqualTo(custom);
        assertThat(listing.listProjectWorkflows("r", "n", project, actor).block()).anySatisfy(item -> {
            assertThat(item.issueTypes()).contains(request);
            assertThat(item.shared()).isFalse();
        });
        var renamed = service.mutate(Operation.UPDATE_WORKFLOW, new WorkflowMutation("r", "n", actor, project,
                custom, 1, null, null, "Service requests", null, null, null, null, Map.of())).block();
        assertThat(renamed.issueTypes()).containsExactly(request);
        assertThat(renamed.workflow().workflow().getName()).isEqualTo("Service requests");
        var deleted = service.mutate(Operation.DELETE_WORKFLOW, new WorkflowMutation("r", "n", actor, project,
                custom, 2, null, null, null, null, null, null, null, Map.of())).block();
        assertThat(deleted.shared()).isTrue();
        assertThat(deleted.issueTypes()).containsExactly(request);
        assertThat(resolver.resolveWorkflow(project, "REQUEST").block().getId()).isEqualTo(fallback.getId());
    }

    @Test
    void deletedOrRecreatedTypeCannotResurrectLegacyBindingAndCleanupRollsBack() {
        // A pre-migration binding has no generation and is valid only for the initial registry generation.
        databaseClient.sql("UPDATE taska.workflow_bindings SET issue_type_generation=NULL WHERE workflow_id=:id")
                .bind("id", workflow).fetch().rowsUpdated().block();
        assertThat(resolver.resolveWorkflow(project, "TASK").block().getId()).isEqualTo(workflow);
        var original = ru.taska.RegistryFixtures.types(project, IssueType.values());
        var removed = ru.taska.RegistryFixtures.types(project, IssueType.BUG, IssueType.STORY);
        registryReturns(removed);
        assertThat(listing.listProjectWorkflows("r", "n", project, actor).block()).noneSatisfy(item ->
                assertThat(item.issueTypes()).contains(IssueType.TASK));
        reactor.test.StepVerifier.create(edits.addStatus("r", "n", actor, project, workflow, status("NEW", 40)))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getStatus()).isEqualTo(DomainStatus.NOT_FOUND)).verify();
        // Simulate a rolled-back registry delete: no cross-service mutation has removed the binding.
        registryReturns(original);
        assertThat(resolver.resolveWorkflow(project, "TASK").block().getId()).isEqualTo(workflow);
        assertThat(bindingCount(workflow)).isEqualTo(1);

        var recreated = original.stream().map(type -> type.getCode().equals("TASK")
                ? type.toBuilder().setGeneration(UUID.randomUUID().toString()).build() : type).toList();
        registryReturns(recreated);
        assertThat(resolver.resolveWorkflow(project, "TASK").block().getId()).isNotEqualTo(workflow);
        assertThat(listing.listProjectWorkflows("r", "n", project, actor).block()).noneSatisfy(item ->
                assertThat(item.workflow().workflow().getId()).isEqualTo(workflow));
        var transaction = org.springframework.transaction.reactive.TransactionalOperator.create(
                new org.springframework.r2dbc.connection.R2dbcTransactionManager(connectionFactory));
        reactor.test.StepVerifier.create(creator.validateAndCreateWorkflow("r", "n", actor, replacement("TASK"))
                        .then(Mono.error(new IllegalStateException("rollback after cleanup"))).as(transaction::transactional))
                .expectError(IllegalStateException.class).verify();
        assertThat(bindingCount(workflow)).isEqualTo(1);
        var fresh = creator.validateAndCreateWorkflow("r", "n", actor, replacement("TASK")).block();
        assertThat(bindingCount(workflow)).isZero();
        assertThat(resolver.resolveWorkflow(project, "TASK").block().getId()).isEqualTo(fresh.workflow().getId());
    }

    @Test
    void inFlightCreationFromOldRegistrySnapshotCannotRestoreOldWorkflowAfterRecreation() throws Exception {
        var old = ru.taska.RegistryFixtures.types(project, IssueType.TASK, IssueType.valueOf("REQUEST"));
        var fresh = old.stream().map(type -> type.getCode().equals("REQUEST")
                ? type.toBuilder().setGeneration(UUID.randomUUID().toString()).build() : type).toList();
        var captured = reactor.core.publisher.Sinks.<Boolean>one();
        var release = reactor.core.publisher.Sinks.<List<ru.taska.api.project.v1.ProjectIssueTypeResponse>>one();
        when(projectClient.listIssueTypeDetails(any(), any(), any(), any())).thenAnswer(inv -> {
            captured.tryEmitValue(true);
            return release.asMono();
        });
        var inFlight = creator.validateAndCreateWorkflow("r", "n", actor, replacement("REQUEST"))
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel()).toFuture();
        captured.asMono().block(java.time.Duration.ofSeconds(5));
        registryReturns(fresh);
        release.tryEmitValue(old);
        var staleWorkflow = inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS).workflow().getId();
        assertThat(bindingCount(staleWorkflow)).isEqualTo(1);
        assertThat(resolver.resolveWorkflow(project, "REQUEST").block().getId()).isNotEqualTo(staleWorkflow);
        var currentWorkflow = creator.validateAndCreateWorkflow("r", "n", actor, replacement("REQUEST")).block().workflow().getId();
        assertThat(bindingCount(staleWorkflow)).isZero();
        assertThat(resolver.resolveWorkflow(project, "REQUEST").block().getId()).isEqualTo(currentWorkflow);
    }

    @Autowired io.r2dbc.spi.ConnectionFactory connectionFactory;

    private void registryReturns(List<ru.taska.api.project.v1.ProjectIssueTypeResponse> types) {
        when(projectClient.listIssueTypeDetails(any(), any(), any(), any())).thenReturn(Mono.just(types));
        when(projectClient.getIssueTypesInternal(any())).thenReturn(Mono.just(types));
    }

    private long bindingCount(UUID workflowId) {
        return databaseClient.sql("SELECT count(*) AS n FROM taska.workflow_bindings WHERE workflow_id=:id")
                .bind("id", workflowId).map((row, metadata) -> row.get("n", Long.class)).one().block();
    }

    private WorkflowCreationDto replacement(String type) {
        return WorkflowCreationDto.builder().projectId(project).name("Replacement")
                .issueTypes(List.of(IssueType.valueOf(type)))
                .statuses(List.of(status("TODO", 10), status("DONE", 20)))
                .transitions(List.of(edge("TODO", "DONE", 10))).build();
    }

    private WorkflowMutation command(String name, UUID status, String target) {
        return new WorkflowMutation("r", "n", actor, project, workflow, 1, status, null,
                name, null, null, null, target, Map.of("REVIEW", "DONE"));
    }

    private CreateWorkflowStatusDto status(String key, int order) {
        return CreateWorkflowStatusDto.builder().statusKey(key).name(key).category(StatusCategory.TODO).sortOrder(order).build();
    }

    private CreateWorkflowTransitionDto edge(String from, String to, int order) {
        return CreateWorkflowTransitionDto.builder().fromStatusKey(from).toStatusKey(to).name(from + " to " + to).sortOrder(order).build();
    }
}

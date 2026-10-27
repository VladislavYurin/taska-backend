package ru.taska.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.registry.v1.IssueTypeDeletionIntent;
import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.dto.WorkflowMutation;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.WorkflowRepository;
import ru.taska.service.*;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;
import ru.taska.transport.grpc.project.IssueTypeDeletionJournalClient;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

/** Real transaction-scoped advisory locks, workflow row locks and durable pin storage. */
class IssueTypeWorkflowReservationIT extends LegacyWorkflowOperationsFixture {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final UUID DEFAULT_WORKFLOW = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @MockitoBean GrpcProjectServiceClient projects;
    @MockitoBean IssueTypeDeletionJournalClient journal;
    @MockitoBean ProjectRoleChecker roles;
    @Autowired IssueTypeWorkflowReservationService reservations;
    @Autowired WorkflowCreateService creates;
    @Autowired WorkflowEditService edits;
    @Autowired WorkflowMutationService mutations;
    @Autowired WorkflowRepository workflows;
    @Autowired ReactiveTransactionManager transactions;
    private UUID project, operation, workflow, actor, sourceGeneration, targetGeneration;
    private final AtomicReference<String> state = new AtomicReference<>();
    private final AtomicReference<List<ProjectIssueTypeResponse>> registry = new AtomicReference<>();

    @org.springframework.test.context.DynamicPropertySource
    static void randomGrpcPort(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.grpc.server.address", () -> "127.0.0.1:0");
        properties.add("spring.grpc.server.port", () -> "0");
    }

    @BeforeEach
    void prepare() {
        project=UUID.randomUUID(); operation=UUID.randomUUID(); workflow=UUID.randomUUID(); actor=UUID.randomUUID();
        sourceGeneration=UUID.randomUUID(); targetGeneration=UUID.randomUUID(); state.set("PENDING");
        registry.set(types(true));
        when(journal.get(any(), any())).thenAnswer(call -> Mono.defer(() -> Mono.just(intent())));
        when(projects.getIssueTypesInternal(any())).thenAnswer(call -> Mono.defer(() -> Mono.just(registry.get())));
        when(projects.listIssueTypeDetails(any(), any(), any(), any())).thenAnswer(call -> Mono.defer(() -> Mono.just(registry.get())));
        when(roles.checkProjectWriteRole(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        databaseClient.sql("INSERT INTO taska.workflows(id,name,version) VALUES(:id,'Target',1)")
                .bind("id",workflow).then().block(TIMEOUT);
        for (String key : List.of("TODO","DONE")) status(key, key.equals("TODO") ? 10 : 30).block(TIMEOUT);
        databaseClient.sql("INSERT INTO taska.transitions(workflow_id,from_status_id,to_status_id,name,sort_order) SELECT :workflow,a.id,b.id,'Finish',10 FROM taska.statuses a JOIN taska.statuses b ON b.workflow_id=a.workflow_id WHERE a.workflow_id=:workflow AND a.status_key='TODO' AND b.status_key='DONE'")
                .bind("workflow",workflow).then().block(TIMEOUT);
        databaseClient.sql("INSERT INTO taska.workflow_bindings(project_id,workflow_id,issue_type,issue_type_generation) VALUES(:project,:workflow,'BUG',:generation)")
                .bind("project",project).bind("workflow",workflow).bind("generation",targetGeneration).then().block(TIMEOUT);
    }

    @Test
    void reservationIsDurableScopedAndIdempotentButCannotReleasePendingOperation() {
        var snapshot=reservations.reserve(project,operation).block(TIMEOUT);
        assertThat(snapshot.getWorkflowId()).isEqualTo(workflow.toString());
        assertThat(snapshot.getValidStatusKeysList()).containsExactly("TODO","DONE");
        assertThat(reservations.get(project,operation).block(TIMEOUT)).isEqualTo(snapshot);
        assertThat(reservations.reserve(project,operation).block(TIMEOUT)).isEqualTo(snapshot);
        assertThat(countPins()).isEqualTo(1);
        rejected(() -> reservations.get(UUID.randomUUID(),operation).block(TIMEOUT),DomainStatus.NOT_FOUND,null);
        rejected(() -> reservations.release(project,operation).block(TIMEOUT),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_OPERATION_NOT_TERMINAL");
        state.set("CANCELLING");
        rejected(() -> reservations.release(project,operation).block(TIMEOUT),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_OPERATION_NOT_TERMINAL");
        assertThat(reservations.get(project,operation).block(TIMEOUT)).isEqualTo(snapshot);
    }

    @Test
    void pendingRegistryFencesCreationAndEveryGraphMutationBeforeLocalPinExists() {
        rejected(() -> creates.validateAndCreateWorkflow(UUID.randomUUID().toString(),"n",actor,creation(IssueType.STORY)).block(TIMEOUT),DomainStatus.ABORTED,"ISSUE_TYPE_OPERATION_PENDING");
        rejected(() -> edits.addStatus("r","n",actor,project,workflow,statusDto("REVIEW")).block(TIMEOUT),DomainStatus.ABORTED,"ISSUE_TYPE_OPERATION_PENDING");
        rejected(() -> edits.addTransition("r","n",actor,project,workflow,transitionDto()).block(TIMEOUT),DomainStatus.ABORTED,"ISSUE_TYPE_OPERATION_PENDING");
        for (var operation : WorkflowMutationService.Operation.values()) {
            rejected(() -> mutations.mutate(operation,mutation()).block(TIMEOUT),DomainStatus.ABORTED,"ISSUE_TYPE_OPERATION_PENDING");
        }
        assertThat(countPins()).isZero();
        assertThat(workflows.findById(workflow).block(TIMEOUT).getVersion()).isEqualTo(1);
    }

    @Test
    void durablePinRemainsAFenceEvenIfRegistryStopsReportingPending() {
        reservations.reserve(project,operation).block(TIMEOUT);
        registry.set(types(false));
        clearInvocations(projects);
        rejected(() -> edits.addStatus("r","n",actor,project,workflow,statusDto("REVIEW")).block(TIMEOUT),DomainStatus.ABORTED,"ISSUE_TYPE_OPERATION_PENDING");
        verify(projects,never()).listIssueTypeDetails(any(),any(),any(),any());
        state.set("COMPLETED"); reservations.release(project,operation).block(TIMEOUT);
        assertThat(edits.addStatus("r","n",actor,project,workflow,statusDto("REVIEW")).block(TIMEOUT)
                .statuses()).extracting(status -> status.getStatusKey()).contains("REVIEW");
    }

    @ParameterizedTest
    @ValueSource(strings={"COMPLETED","CANCELLED"})
    void terminalCleanupNeedsNoCurrentActorPermissionAndRetainsAnIdempotentTombstone(String terminal) {
        reservations.reserve(project,operation).block(TIMEOUT);
        state.set(terminal);
        when(roles.checkProjectWriteRole(any(),any(),any(),any(),any()))
                .thenReturn(Mono.error(new DomainException(DomainStatus.PERMISSION_DENIED,"Actor removed")));
        when(projects.getIssueTypesInternal(any())).thenReturn(Mono.error(new DomainException(DomainStatus.UNAVAILABLE,"Registry unavailable")));
        reservations.release(project,operation).block(TIMEOUT);
        reservations.release(project,operation).block(TIMEOUT);
        assertThat(countPins()).isEqualTo(1);
        assertThat(databaseClient.sql("SELECT released FROM taska.issue_type_move_workflow_pins WHERE operation_id=:id")
                .bind("id",operation).map(row -> row.get(0,Boolean.class)).one().block(TIMEOUT)).isTrue();
        rejected(() -> reservations.get(project,operation).block(TIMEOUT),DomainStatus.NOT_FOUND,null);
        rejected(() -> reservations.reserve(project,operation).block(TIMEOUT),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPE_OPERATION_NOT_PENDING");
        verifyNoInteractions(roles);
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void staleGenerationOrForeignPendingOperationCannotCreateOrReuseAPin(boolean afterReservation) {
        if (afterReservation) reservations.reserve(project,operation).block(TIMEOUT);
        var changed=new ArrayList<>(registry.get());
        changed.set(1,changed.get(1).toBuilder().setGeneration(UUID.randomUUID().toString()).build());
        registry.set(changed);
        rejected(() -> reservations.reserve(project,operation).block(TIMEOUT),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPES_CHANGED");
        changed.set(1,types(true).get(1).toBuilder().setPendingOperationId(UUID.randomUUID().toString()).build());
        registry.set(changed);
        rejected(() -> reservations.reserve(project,operation).block(TIMEOUT),DomainStatus.FAILED_PRECONDITION,"ISSUE_TYPES_CHANGED");
        assertThat(countPins()).isEqualTo(afterReservation ? 1 : 0);
    }

    @Test
    void deletedAndRecreatedTypeDoesNotReuseThePreviousGenerationBinding() {
        databaseClient.sql("UPDATE taska.workflow_bindings SET issue_type_generation=:old WHERE project_id=:project")
                .bind("old",UUID.randomUUID()).bind("project",project).then().block(TIMEOUT);
        var snapshot=reservations.reserve(project,operation).block(TIMEOUT);
        assertThat(snapshot.getWorkflowId()).isEqualTo(DEFAULT_WORKFLOW.toString());
        assertThat(snapshot.getValidStatusKeysList()).contains("IN_PROGRESS");
    }

    @Test
    void reserveDrainsAnOlderWorkflowRowMutationAndNewGraphWriteWaitsThenFails() throws Exception {
        var release=Sinks.<Void>one(); var held=new CountDownLatch(1);
        var oldWriter=TransactionalOperator.create(transactions).transactional(workflows.findByIdForUpdate(workflow)
                .then(status("REVIEW",20)).then(Mono.fromRunnable(held::countDown)).then(release.asMono())).toFuture();
        try {
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var reserve=reservations.reserve(project,operation).toFuture();
            awaitLock("taska.workflows");
            var laterWrite=edits.addStatus("r","n",actor,project,workflow,statusDto("AFTER_PIN")).materialize().toFuture();
            awaitLock("hashtextextended");
            release.tryEmitEmpty(); oldWriter.get(10,TimeUnit.SECONDS);
            assertThat(reserve.get(10,TimeUnit.SECONDS).getValidStatusKeysList()).containsExactly("TODO","REVIEW","DONE");
            assertThat(laterWrite.get(10,TimeUnit.SECONDS).getThrowable()).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getStatus()).isEqualTo(DomainStatus.ABORTED));
        } finally { release.tryEmitEmpty(); }
    }

    @Test
    void reservationSeesPublishedCreationBeforeTheOuterCallerFinishes() throws Exception {
        databaseClient.sql("DELETE FROM taska.workflow_bindings WHERE project_id=:project").bind("project",project).then().block(TIMEOUT);
        registry.set(types(false));
        var created=new AtomicReference<UUID>(); var release=Sinks.<Void>one(); var held=new CountDownLatch(1);
        var creation=TransactionalOperator.create(transactions).transactional(
                creates.validateAndCreateWorkflow(UUID.randomUUID().toString(),"n",actor,creation(IssueType.BUG))
                        .doOnNext(result -> created.set(result.workflow().getId())).then(Mono.fromRunnable(held::countDown))
                        .then(release.asMono())).toFuture();
        try {
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            registry.set(types(true));
            var reserve=reservations.reserve(project,operation).toFuture();
            assertThat(reserve.get(10,TimeUnit.SECONDS).getWorkflowId()).isEqualTo(created.get().toString());
            release.tryEmitEmpty(); creation.get(10,TimeUnit.SECONDS);
        } finally { release.tryEmitEmpty(); }
    }

    private Mono<Void> status(String key,int order) {
        return databaseClient.sql("INSERT INTO taska.statuses(workflow_id,status_key,name,category,sort_order) VALUES(:workflow,:key,:key,:category,:sort)")
                .bind("workflow",workflow).bind("key",key).bind("category",key.equals("TODO") ? "TODO" : key.equals("DONE") ? "DONE" : "IN_PROGRESS")
                .bind("sort",order).then();
    }
    private List<ProjectIssueTypeResponse> types(boolean pending) {
        return List.of(type("TASK",sourceGeneration,pending),type("BUG",targetGeneration,pending),type("STORY",UUID.randomUUID(),false));
    }
    private ProjectIssueTypeResponse type(String code,UUID generation,boolean pending) {
        return ProjectIssueTypeResponse.newBuilder().setCode(code).setGeneration(generation.toString())
                .setPendingOperationId(pending ? operation.toString() : "").build();
    }
    private IssueTypeDeletionIntent intent() {
        return IssueTypeDeletionIntent.newBuilder().setProjectId(project.toString()).setOperationId(operation.toString())
                .setSourceCode("TASK").setSourceGeneration(sourceGeneration.toString()).setTargetCode("BUG")
                .setTargetGeneration(targetGeneration.toString()).setActorUserId(actor.toString()).setState(state.get()).build();
    }
    private CreateWorkflowStatusDto statusDto(String key) {
        return CreateWorkflowStatusDto.builder().statusKey(key).name(key).category(StatusCategory.IN_PROGRESS).sortOrder(20).build();
    }
    private CreateWorkflowTransitionDto transitionDto() {
        return CreateWorkflowTransitionDto.builder().name("Finish").fromStatusKey("TODO").toStatusKey("DONE").sortOrder(10).build();
    }
    private WorkflowCreationDto creation(IssueType type) {
        return WorkflowCreationDto.builder().projectId(project).name("New workflow").issueTypes(List.of(type))
                .statuses(List.of(CreateWorkflowStatusDto.builder().statusKey("TODO").name("Todo").category(StatusCategory.TODO).sortOrder(10).build(),
                        CreateWorkflowStatusDto.builder().statusKey("DONE").name("Done").category(StatusCategory.DONE).sortOrder(30).build()))
                .transitions(List.of(transitionDto())).build();
    }
    private WorkflowMutation mutation() {
        return new WorkflowMutation("r","n",actor,project,workflow,null,UUID.randomUUID(),UUID.randomUUID(),"Renamed",null,null,null,null,Map.of());
    }
    private long countPins() {
        return databaseClient.sql("SELECT count(*) FROM taska.issue_type_move_workflow_pins WHERE project_id=:project")
                .bind("project",project).map(row -> row.get(0,Long.class)).one().block(TIMEOUT);
    }
    private void awaitLock(String query) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> databaseClient.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE :query")
                .bind("query","%"+query+"%").map(row -> row.get(0,Long.class)).one().block(TIMEOUT)>0);
    }
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,DomainStatus status,String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class,error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            if (reason!=null) assertThat(error.getDetails()).containsEntry("reason",reason);
        });
    }
}

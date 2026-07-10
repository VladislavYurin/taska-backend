package ru.taska.integration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.CreateWorkflowRequestBody;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.api.workflow.v1.WorkflowStatusCreateRequest;
import ru.taska.api.workflow.v1.WorkflowTransitionCreateRequest;

import ru.taska.domain.IssueType;
import ru.taska.domain.StatusCategory;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.domain.WorkflowAggregate;
import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.service.WorkflowService;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class WorkflowCreationIT extends AbstractIT {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "some-service";
    private static final UUID PROJECT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ACTOR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowBindingRepository workflowBindingRepository;

    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;

    @MockitoSpyBean
    private TransitionRepository transitionRepository;

    private ManagedChannel channel;
    private ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub stub;

    private WorkflowCreationDto validDto;

    @BeforeEach
    void setUpGrpcClient() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .usePlaintext()
                .build();
        stub = ReactorWorkflowServiceGrpc.newReactorStub(channel);
    }

    @AfterEach
    void tearDownGrpcClient() {
        channel.shutdownNow();
    }

    @BeforeEach
    void cleanUp() {
        databaseClient.sql("DELETE FROM taska.workflow_bindings WHERE project_id = :projectId")
                .bind("projectId", PROJECT_ID)
                .fetch()
                .rowsUpdated()
                .then(databaseClient.sql("""
                        DELETE FROM taska.workflows
                        WHERE id NOT IN (SELECT workflow_id FROM taska.workflow_bindings)
                        """)
                        .fetch()
                        .rowsUpdated())
                .block();
    }

    @BeforeEach
    void stubRoleChecker() {
        Mockito.when(projectRoleChecker.checkProjectRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()))
                .thenReturn(Mono.empty());
    }

    @BeforeEach
    void setUp() {
        validDto = WorkflowCreationDto.builder()
                .projectId(PROJECT_ID)
                .name("Test Workflow")
                .issueTypes(new ArrayList<>(List.of(IssueType.TASK)))
                .statuses(new ArrayList<>(List.of(
                        CreateWorkflowStatusDto.builder()
                                .statusKey("TODO")
                                .name("To Do")
                                .category(StatusCategory.TODO)
                                .sortOrder(0)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("IN_PROGRESS")
                                .name("In Progress")
                                .category(StatusCategory.IN_PROGRESS)
                                .sortOrder(1)
                                .build(),
                        CreateWorkflowStatusDto.builder()
                                .statusKey("DONE")
                                .name("Done")
                                .category(StatusCategory.DONE)
                                .sortOrder(2)
                                .build()
                )))
                .transitions(new ArrayList<>(List.of(
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("TODO")
                                .toStatusKey("IN_PROGRESS")
                                .name("Start")
                                .sortOrder(0)
                                .build(),
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("IN_PROGRESS")
                                .toStatusKey("DONE")
                                .name("Finish")
                                .sortOrder(1)
                                .build()
                )))
                .build();
    }

    @Test
    void createWorkflow_ValidDto_ShouldPersistWorkflowStatusesTransitionsAndBinding() {
        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto).block();

        WorkflowAggregate aggregate = workflowService.getWorkflow(PROJECT_ID, "TASK").block();

        Assertions.assertNotNull(aggregate);

        Assertions.assertEquals("Test Workflow", aggregate.workflow().getName());
        Assertions.assertTrue(aggregate.workflow().isActive());

        List<StatusEntity> statuses = aggregate.statuses();
        Assertions.assertEquals(3, statuses.size());
        Assertions.assertEquals("TODO", statuses.get(0).getStatusKey());
        Assertions.assertEquals("To Do", statuses.get(0).getName());
        Assertions.assertEquals(StatusCategory.TODO.name(), statuses.get(0).getCategory());
        Assertions.assertEquals(0, statuses.get(0).getSortOrder());
        Assertions.assertEquals("IN_PROGRESS", statuses.get(1).getStatusKey());
        Assertions.assertEquals("In Progress", statuses.get(1).getName());
        Assertions.assertEquals(StatusCategory.IN_PROGRESS.name(), statuses.get(1).getCategory());
        Assertions.assertEquals(1, statuses.get(1).getSortOrder());
        Assertions.assertEquals("DONE", statuses.get(2).getStatusKey());
        Assertions.assertEquals("Done", statuses.get(2).getName());
        Assertions.assertEquals(StatusCategory.DONE.name(), statuses.get(2).getCategory());
        Assertions.assertEquals(2, statuses.get(2).getSortOrder());

        List<TransitionEntity> transitions = aggregate.transitions();
        Assertions.assertEquals(2, transitions.size());
        Assertions.assertEquals("Start", transitions.get(0).getName());
        Assertions.assertEquals(0, transitions.get(0).getSortOrder());
        Assertions.assertEquals("Finish", transitions.get(1).getName());
        Assertions.assertEquals(1, transitions.get(1).getSortOrder());

        UUID workflowId = aggregate.workflow().getId();
        StepVerifier.create(workflowBindingRepository.findByProjectIdAndIssueTypeIn(PROJECT_ID, List.of("TASK")))
                .assertNext(binding -> {
                    Assertions.assertEquals(PROJECT_ID, binding.getProjectId());
                    Assertions.assertEquals("TASK", binding.getIssueType());
                    Assertions.assertEquals(workflowId, binding.getWorkflowId());
                })
                .verifyComplete();
    }

    @Test
    void createWorkflow_WithTwoIssueTypes_ShouldBindBothTypes() {
        validDto.setIssueTypes(new ArrayList<>(List.of(IssueType.TASK, IssueType.BUG)));

        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto).block();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "TASK"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Test Workflow", aggregate.workflow().getName()))
                .verifyComplete();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "BUG"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Test Workflow", aggregate.workflow().getName()))
                .verifyComplete();
    }

    @Test
    void getWorkflow_ReturnsCustomForBoundTypeAndDefaultForUnboundType() {
        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto).block();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "TASK"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Test Workflow", aggregate.workflow().getName()))
                .verifyComplete();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "BUG"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Default workflow", aggregate.workflow().getName()))
                .verifyComplete();
    }

    @Test
    void createWorkflow_BindingAlreadyExists_ShouldReturnAlreadyExists() {
        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto).block();

        StepVerifier.create(workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto))
                .expectErrorSatisfies(error -> {
                    DomainException ex = Assertions.assertInstanceOf(DomainException.class, error);
                    Assertions.assertEquals(DomainStatus.ALREADY_EXISTS, ex.getStatus());
                })
                .verify();
    }

    @Test
    void createWorkflow_DifferentIssueTypes_ShouldReturnIndependentWorkflows() {
        WorkflowCreationDto bugWorkflowDto = WorkflowCreationDto.builder()
                .projectId(PROJECT_ID)
                .name("Bug Workflow")
                .issueTypes(new ArrayList<>(List.of(IssueType.BUG)))
                .statuses(validDto.getStatuses())
                .transitions(validDto.getTransitions())
                .build();

        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto).block();
        workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, bugWorkflowDto).block();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "TASK"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Test Workflow", aggregate.workflow().getName()))
                .verifyComplete();

        StepVerifier.create(workflowService.getWorkflow(PROJECT_ID, "BUG"))
                .assertNext(aggregate ->
                        Assertions.assertEquals("Bug Workflow", aggregate.workflow().getName()))
                .verifyComplete();
    }

    @Test
    void createWorkflow_MissingTodoStatus_ShouldReturnInvalidArgument() {
        validDto.getStatuses().removeIf(s -> s.getStatusKey().equals("TODO"));

        StepVerifier.create(workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto))
                .expectErrorSatisfies(error -> {
                    DomainException ex = Assertions.assertInstanceOf(DomainException.class, error);
                    Assertions.assertEquals(DomainStatus.INVALID_ARGUMENT, ex.getStatus());
                })
                .verify();
    }

    @Test
    void createWorkflow_FailureDuringSave_ShouldRollbackWorkflowAndStatuses() {
        // Провоцируем ошибку при сохранении переходов — workflow и статусы к этому моменту уже
        // записаны в рамках открытой транзакции, но должны откатиться вместе с ней.
        Mockito.doReturn(Flux.error(new RuntimeException("Simulated DB failure")))
                .when(transitionRepository).saveAll(Mockito.<Iterable<TransitionEntity>>any());

        StepVerifier.create(workflowService.createWorkflow(REQUEST_ID, NODE_ID, ACTOR_USER_ID, validDto))
                .expectError(RuntimeException.class)
                .verify();

        Long workflowCount = databaseClient
                .sql("SELECT COUNT(*) FROM taska.workflows WHERE name = :name")
                .bind("name", "Test Workflow")
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        Assertions.assertEquals(0L, workflowCount, "Workflow не должен остаться в БД после отката транзакции");

        Long statusCount = databaseClient
                .sql("""
                        SELECT COUNT(*) FROM taska.statuses s
                        JOIN taska.workflows w ON s.workflow_id = w.id
                        WHERE w.name = :name
                        """)
                .bind("name", "Test Workflow")
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        Assertions.assertEquals(0L, statusCount, "Статусы не должны остаться в БД после отката транзакции");

        Long bindingCount = databaseClient
                .sql("SELECT COUNT(*) FROM taska.workflow_bindings WHERE project_id = :projectId")
                .bind("projectId", PROJECT_ID)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        Assertions.assertEquals(0L, bindingCount, "Байндинги не должны остаться в БД после отката транзакции");
    }

    @Test
    void createWorkflow_ValidRequest_ShouldReturnWorkflowResponse() {
        StepVerifier.create(stub.createWorkflow(Mono.just(buildValidGrpcRequest())))
                .assertNext(response -> {
                    Assertions.assertFalse(response.getId().isBlank());
                    Assertions.assertEquals("Test Workflow", response.getName());
                    Assertions.assertEquals(3, response.getStatusesCount());
                    Assertions.assertEquals(2, response.getTransitionsCount());
                })
                .verifyComplete();
    }

    @Test
    void createWorkflow_InvalidProjectId_ShouldReturnInvalidArgument() {
        CreateWorkflowRequest request = CreateWorkflowRequest.newBuilder()
                .setHeader(buildGrpcHeader())
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId("not-a-uuid")
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setName("Test Workflow")
                        .addIssueTypes(ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK)
                        .addAllStatuses(buildGrpcStatuses())
                        .addAllTransitions(buildGrpcTransitions())
                        .build())
                .build();

        StepVerifier.create(stub.createWorkflow(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    StatusRuntimeException ex = Assertions.assertInstanceOf(StatusRuntimeException.class, error);
                    Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
                })
                .verify();
    }

    @Test
    void createWorkflow_EmptyIssueTypesList_ShouldReturnInvalidArgument() {
        CreateWorkflowRequest request = CreateWorkflowRequest.newBuilder()
                .setHeader(buildGrpcHeader())
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setName("Test Workflow")
                        .addAllStatuses(buildGrpcStatuses())
                        .addAllTransitions(buildGrpcTransitions())
                        .build())
                .build();

        StepVerifier.create(stub.createWorkflow(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    StatusRuntimeException ex = Assertions.assertInstanceOf(StatusRuntimeException.class, error);
                    Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
                })
                .verify();
    }

    @Test
    void createWorkflow_UnspecifiedIssueType_ShouldReturnInvalidArgument() {
        CreateWorkflowRequest request = CreateWorkflowRequest.newBuilder()
                .setHeader(buildGrpcHeader())
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setName("Test Workflow")
                        .addIssueTypes(ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_UNSPECIFIED)
                        .addAllStatuses(buildGrpcStatuses())
                        .addAllTransitions(buildGrpcTransitions())
                        .build())
                .build();

        StepVerifier.create(stub.createWorkflow(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    StatusRuntimeException ex = Assertions.assertInstanceOf(StatusRuntimeException.class, error);
                    Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
                })
                .verify();
    }

    @Test
    void createWorkflow_UnspecifiedStatusCategory_ShouldReturnInvalidArgument() {
        List<WorkflowStatusCreateRequest> statuses = List.of(
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("TODO")
                        .setName("To Do")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_UNSPECIFIED)
                        .setSortOrder(0)
                        .build(),
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("IN_PROGRESS")
                        .setName("In Progress")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                        .setSortOrder(1)
                        .build(),
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("DONE")
                        .setName("Done")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_DONE)
                        .setSortOrder(2)
                        .build()
        );

        CreateWorkflowRequest request = CreateWorkflowRequest.newBuilder()
                .setHeader(buildGrpcHeader())
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setName("Test Workflow")
                        .addIssueTypes(ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK)
                        .addAllStatuses(statuses)
                        .addAllTransitions(buildGrpcTransitions())
                        .build())
                .build();

        StepVerifier.create(stub.createWorkflow(Mono.just(request)))
                .expectErrorSatisfies(error -> {
                    StatusRuntimeException ex = Assertions.assertInstanceOf(StatusRuntimeException.class, error);
                    Assertions.assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
                })
                .verify();
    }

    private CreateWorkflowRequest buildValidGrpcRequest() {
        return CreateWorkflowRequest.newBuilder()
                .setHeader(buildGrpcHeader())
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setName("Test Workflow")
                        .addIssueTypes(ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK)
                        .addAllStatuses(buildGrpcStatuses())
                        .addAllTransitions(buildGrpcTransitions())
                        .build())
                .build();
    }

    private Header buildGrpcHeader() {
        return Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }

    private List<WorkflowStatusCreateRequest> buildGrpcStatuses() {
        return List.of(
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("TODO")
                        .setName("To Do")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_TODO)
                        .setSortOrder(0)
                        .build(),
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("IN_PROGRESS")
                        .setName("In Progress")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                        .setSortOrder(1)
                        .build(),
                WorkflowStatusCreateRequest.newBuilder()
                        .setStatusKey("DONE")
                        .setName("Done")
                        .setCategory(ru.taska.api.workflow.v1.StatusCategory.STATUS_CATEGORY_DONE)
                        .setSortOrder(2)
                        .build()
        );
    }

    private List<WorkflowTransitionCreateRequest> buildGrpcTransitions() {
        return List.of(
                WorkflowTransitionCreateRequest.newBuilder()
                        .setFromStatusKey("TODO")
                        .setToStatusKey("IN_PROGRESS")
                        .setName("Start")
                        .setSortOrder(0)
                        .build(),
                WorkflowTransitionCreateRequest.newBuilder()
                        .setFromStatusKey("IN_PROGRESS")
                        .setToStatusKey("DONE")
                        .setName("Finish")
                        .setSortOrder(1)
                        .build()
        );
    }

}

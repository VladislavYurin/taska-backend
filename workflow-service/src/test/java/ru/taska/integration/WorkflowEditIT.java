package ru.taska.integration;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequest;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequestBody;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequest;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequestBody;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequestBody;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowStatusCreateRequest;
import ru.taska.api.workflow.v1.WorkflowTransitionCreateRequest;
import ru.taska.dto.CreateWorkflowStatusDto;
import ru.taska.dto.CreateWorkflowTransitionDto;
import ru.taska.dto.ValidateTransitionResponseDto;
import ru.taska.dto.WorkflowCreationDto;
import ru.taska.service.WorkflowService;
import ru.taska.transport.grpc.GrpcWorkflowServiceAdapter;
import ru.taska.transport.grpc.project.ProjectRoleChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Изменение и чтение workflow проекта через gRPC-адаптер, недостающие переходы default workflow.
 * <p>
 * Адаптер вызывается напрямую, без сети: так проверяется весь путь до статуса gRPC, включая
 * {@code GrpcExceptionHandler}, и тест не зависит от порта gRPC-сервера в тестовом контексте.
 */
class WorkflowEditIT extends LegacyWorkflowOperationsFixture {

    private static final String REQUEST_ID = "req-edit";
    private static final String NODE_ID = "some-service";
    private static final UUID PROJECT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ACTOR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DEFAULT_WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STOP_PROGRESS_TRANSITION_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private GrpcWorkflowServiceAdapter stub;

    @MockitoBean
    private ProjectRoleChecker projectRoleChecker;

    @MockitoBean
    private ru.taska.transport.grpc.project.GrpcProjectServiceClient projectClient;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(projectClient.listIssueTypes(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(java.util.List.of(ru.taska.domain.IssueType.values())));
        org.mockito.Mockito.when(projectClient.listIssueTypeDetails(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(ru.taska.RegistryFixtures.types(inv.getArgument(2), ru.taska.domain.IssueType.values())));
        org.mockito.Mockito.when(projectClient.getIssueTypesInternal(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(ru.taska.RegistryFixtures.types(inv.getArgument(0), ru.taska.domain.IssueType.values())));


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

        Mockito.when(projectRoleChecker.checkProjectWriteRole(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.any(UUID.class),
                        Mockito.any(UUID.class),
                        Mockito.anySet()))
                .thenReturn(Mono.empty());
    }

    @Test
    void defaultWorkflow_ShouldAllowStopProgressBackToTodo() {
        ValidateTransitionResponseDto response = workflowService.validateTransition(REQUEST_ID, NODE_ID, PROJECT_ID,
                "TASK", STOP_PROGRESS_TRANSITION_ID, "IN_PROGRESS", null, ACTOR_USER_ID).block();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.isValid());
        Assertions.assertEquals("TODO", response.getToStatusKey());
    }

    @Test
    void listProjectWorkflows_ProjectWithoutOwnWorkflow_ShouldReturnSharedDefaultWithAllTransitions() {
        StepVerifier.create(stub.listProjectWorkflows(Mono.just(listRequest())))
                .assertNext(response -> {
                    Assertions.assertEquals(1, response.getWorkflowsCount());
                    ProjectWorkflow shared = response.getWorkflows(0);
                    Assertions.assertTrue(shared.getIsShared());
                    Assertions.assertEquals(DEFAULT_WORKFLOW_ID.toString(), shared.getWorkflow().getId());
                    Assertions.assertEquals(
                            List.of(IssueType.ISSUE_TYPE_TASK, IssueType.ISSUE_TYPE_BUG, IssueType.ISSUE_TYPE_STORY),
                            shared.getIssueTypesList());
                    Assertions.assertEquals(
                            Map.of("TODO", List.of("IN_PROGRESS", "DONE"),
                                    "IN_PROGRESS", List.of("DONE", "TODO"),
                                    "DONE", List.of("IN_PROGRESS", "TODO")),
                            transitionsByStatusKey(shared.getWorkflow()));
                })
                .verifyComplete();
    }

    @Test
    void addStatusAndTransition_ProjectWorkflow_ShouldBeVisibleInProjectWorkflows() {
        String workflowId = workflowService.createWorkflow(UUID.randomUUID().toString(), NODE_ID, ACTOR_USER_ID, projectWorkflow())
                .block()
                .workflow()
                .getId()
                .toString();

        StepVerifier.create(stub.addWorkflowStatus(Mono.just(addStatusRequest(workflowId, "CODE_REVIEW", 15))))
                .assertNext(response -> {
                    Assertions.assertEquals(2, response.getVersion());
                    Assertions.assertTrue(response.getStatusesList().stream()
                            .anyMatch(s -> s.getStatusKey().equals("CODE_REVIEW")
                                    && s.getCategory() == StatusCategory.STATUS_CATEGORY_IN_PROGRESS));
                })
                .verifyComplete();

        StepVerifier.create(stub.addWorkflowTransition(Mono.just(addTransitionRequest(workflowId, "CODE_REVIEW", "TODO", 5, true))))
                .assertNext(response -> Assertions.assertEquals(3, response.getVersion()))
                .verifyComplete();

        StepVerifier.create(stub.listProjectWorkflows(Mono.just(listRequest())))
                .assertNext(response -> {
                    Assertions.assertEquals(2, response.getWorkflowsCount());
                    ProjectWorkflow own = findWorkflow(response, workflowId);
                    Assertions.assertFalse(own.getIsShared());
                    Assertions.assertEquals(List.of(IssueType.ISSUE_TYPE_TASK), own.getIssueTypesList());
                    Assertions.assertEquals(List.of("TODO", "CODE_REVIEW", "IN_PROGRESS", "DONE"),
                            own.getWorkflow().getStatusesList().stream().map(WorkflowStatus::getStatusKey).toList());
                    Assertions.assertTrue(own.getWorkflow().getTransitionsList().stream()
                            .anyMatch(t -> t.getIsHidden() && t.getSortOrder() == 5),
                            "Hidden transition must be listed");

                    ProjectWorkflow shared = findWorkflow(response, DEFAULT_WORKFLOW_ID.toString());
                    Assertions.assertTrue(shared.getIsShared());
                    Assertions.assertEquals(List.of(IssueType.ISSUE_TYPE_BUG, IssueType.ISSUE_TYPE_STORY),
                            shared.getIssueTypesList());
                })
                .verifyComplete();
    }

    @Test
    void addTransition_ExistingPair_ShouldReturnAlreadyExists() {
        String workflowId = workflowService.createWorkflow(UUID.randomUUID().toString(), NODE_ID, ACTOR_USER_ID, projectWorkflow())
                .block()
                .workflow()
                .getId()
                .toString();

        StepVerifier.create(stub.addWorkflowTransition(Mono.just(addTransitionRequest(workflowId, "TODO", "IN_PROGRESS", 50, false))))
                .expectErrorSatisfies(error -> assertGrpcCode(error, io.grpc.Status.Code.ALREADY_EXISTS))
                .verify();
    }

    @Test
    void addStatus_DefaultWorkflow_ShouldReturnFailedPrecondition() {
        StepVerifier.create(stub.addWorkflowStatus(Mono.just(addStatusRequest(DEFAULT_WORKFLOW_ID.toString(), "CODE_REVIEW", 15))))
                .expectErrorSatisfies(error -> assertGrpcCode(error, io.grpc.Status.Code.FAILED_PRECONDITION))
                .verify();
    }

    @Test
    void addStatus_InvalidWorkflowId_ShouldReturnInvalidArgument() {
        StepVerifier.create(stub.addWorkflowStatus(Mono.just(addStatusRequest("not-a-uuid", "CODE_REVIEW", 15))))
                .expectErrorSatisfies(error -> assertGrpcCode(error, io.grpc.Status.Code.INVALID_ARGUMENT))
                .verify();
    }

    @Test
    void transitions_DuplicatePairInDatabase_ShouldBeRejectedByUniqueIndex() {
        Mono<Long> duplicate = databaseClient.sql("""
                        INSERT INTO taska.transitions (workflow_id, from_status_id, to_status_id, name, sort_order)
                        VALUES (:workflowId, '22222222-2222-2222-2222-222222222222'::uuid,
                                '33333333-3333-3333-3333-333333333333'::uuid, 'Start Progress again', 99)
                        """)
                .bind("workflowId", DEFAULT_WORKFLOW_ID)
                .fetch()
                .rowsUpdated();

        StepVerifier.create(duplicate)
                .expectError()
                .verify();
    }

    @Test
    void customTypeUsesGeneralDefaultThenItsOwnWorkflowAndRegistryList() {
        var request = ru.taska.domain.IssueType.valueOf("REQUEST");
        Mockito.when(projectClient.listIssueTypes(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(List.of(request)));
        Mockito.when(projectClient.listIssueTypeDetails(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(PROJECT_ID, request)));
        Mockito.when(projectClient.getIssueTypesInternal(Mockito.any()))
                .thenReturn(Mono.just(ru.taska.RegistryFixtures.types(PROJECT_ID, request)));
        var initial = workflowService.getWorkflow(PROJECT_ID, "REQUEST").block();
        Assertions.assertEquals(DEFAULT_WORKFLOW_ID, initial.workflow().getId());
        var reply = stub.getWorkflowForProject(Mono.just(ru.taska.api.workflow.v1.GetWorkflowForProjectRequest.newBuilder()
                .setHeader(header()).setBody(ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString()).setActorUserId(ACTOR_USER_ID.toString()).setIssueTypeKey("REQUEST")).build())).block();
        Assertions.assertEquals(List.of("REQUEST"), reply.getIssueTypeKeysList());
        var todo = initial.statuses().stream().filter(status -> status.getStatusKey().equals("TODO")).findFirst().orElseThrow();
        var transition = initial.transitions().stream().filter(edge -> edge.getFromStatusId().equals(todo.getId())).findFirst().orElseThrow();
        var validated = stub.validateTransition(Mono.just(ru.taska.api.workflow.v1.ValidateTransitionRequest.newBuilder()
                .setHeader(header()).setBody(ru.taska.api.workflow.v1.ValidateTransitionRequestBody.newBuilder()
                        .setActorUserId(ACTOR_USER_ID.toString()).setTransitionId(transition.getId().toString())
                        .setIssueSnapshot(ru.taska.api.workflow.v1.IssueValidateSnapshot.newBuilder().setIssueId(UUID.randomUUID().toString())
                                .setProjectId(PROJECT_ID.toString()).setIssueTypeKey("REQUEST").setStatusKey("TODO"))).build())).block();
        Assertions.assertTrue(validated.getBody().getIsValid());
        var shared = stub.listProjectWorkflows(Mono.just(listRequest())).block().getWorkflows(0);
        Assertions.assertEquals(List.of("REQUEST"), shared.getIssueTypeKeysList());
        Assertions.assertTrue(shared.getIsShared());

        var dto = projectWorkflow();
        dto.setIssueTypes(List.of(request));
        var own = workflowService.createWorkflow(UUID.randomUUID().toString(), NODE_ID, ACTOR_USER_ID, dto).block();
        Assertions.assertEquals(own.workflow().getId(), workflowService.getWorkflow(PROJECT_ID, "REQUEST").block().workflow().getId());
        var listed = stub.listProjectWorkflows(Mono.just(listRequest())).block();
        Assertions.assertEquals(1, listed.getWorkflowsCount());
        Assertions.assertEquals(List.of("REQUEST"), listed.getWorkflows(0).getIssueTypeKeysList());
        Assertions.assertFalse(listed.getWorkflows(0).getIsShared());
        dto.setIssueTypes(List.of(ru.taska.domain.IssueType.BUG));
        StepVerifier.create(workflowService.createWorkflow(UUID.randomUUID().toString(), NODE_ID, ACTOR_USER_ID, dto))
                .expectErrorSatisfies(e -> Assertions.assertEquals(ru.taska.exception.DomainStatus.INVALID_ARGUMENT,
                        ((ru.taska.exception.DomainException) e).getStatus())).verify();
    }

    private static void assertGrpcCode(Throwable error, io.grpc.Status.Code expected) {
        StatusRuntimeException ex = Assertions.assertInstanceOf(StatusRuntimeException.class, error);
        Assertions.assertEquals(expected, ex.getStatus().getCode());
    }

    private static ProjectWorkflow findWorkflow(ListProjectWorkflowsResponse response, String workflowId) {
        return response.getWorkflowsList().stream()
                .filter(w -> w.getWorkflow().getId().equals(workflowId))
                .findFirst()
                .orElseThrow();
    }

    // Ключ исходного статуса -> ключи целевых статусов в порядке sortOrder переходов.
    private static Map<String, List<String>> transitionsByStatusKey(WorkflowResponse workflow) {
        Map<String, String> keyById = workflow.getStatusesList().stream()
                .collect(Collectors.toMap(WorkflowStatus::getId, WorkflowStatus::getStatusKey));
        return workflow.getTransitionsList().stream()
                .collect(Collectors.groupingBy(t -> keyById.get(t.getFromStatusId()),
                        Collectors.mapping(t -> keyById.get(t.getToStatusId()), Collectors.toList())));
    }

    private static WorkflowCreationDto projectWorkflow() {
        return WorkflowCreationDto.builder()
                .projectId(PROJECT_ID)
                .name("Project workflow")
                .issueTypes(new ArrayList<>(List.of(ru.taska.domain.IssueType.TASK)))
                .statuses(new ArrayList<>(List.of(
                        status("TODO", ru.taska.domain.StatusCategory.TODO, 10),
                        status("IN_PROGRESS", ru.taska.domain.StatusCategory.IN_PROGRESS, 20),
                        status("DONE", ru.taska.domain.StatusCategory.DONE, 30))))
                .transitions(new ArrayList<>(List.of(
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("TODO")
                                .toStatusKey("IN_PROGRESS")
                                .name("Start Progress")
                                .sortOrder(10)
                                .build(),
                        CreateWorkflowTransitionDto.builder()
                                .fromStatusKey("IN_PROGRESS")
                                .toStatusKey("DONE")
                                .name("Complete")
                                .sortOrder(20)
                                .build())))
                .build();
    }

    private static CreateWorkflowStatusDto status(String key, ru.taska.domain.StatusCategory category, int sortOrder) {
        return CreateWorkflowStatusDto.builder()
                .statusKey(key)
                .name(key)
                .category(category)
                .sortOrder(sortOrder)
                .build();
    }

    private static Header header() {
        return Header.newBuilder()
                .setRequestId(REQUEST_ID)
                .setNodeId(NODE_ID)
                .build();
    }

    private static ListProjectWorkflowsRequest listRequest() {
        return ListProjectWorkflowsRequest.newBuilder()
                .setHeader(header())
                .setBody(ListProjectWorkflowsRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_USER_ID.toString()))
                .build();
    }

    private static AddWorkflowStatusRequest addStatusRequest(String workflowId, String statusKey, int sortOrder) {
        return AddWorkflowStatusRequest.newBuilder()
                .setHeader(header())
                .setBody(AddWorkflowStatusRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setWorkflowId(workflowId)
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setStatus(WorkflowStatusCreateRequest.newBuilder()
                                .setStatusKey(statusKey)
                                .setName("Code Review")
                                .setCategory(StatusCategory.STATUS_CATEGORY_IN_PROGRESS)
                                .setSortOrder(sortOrder)))
                .build();
    }

    private static AddWorkflowTransitionRequest addTransitionRequest(String workflowId, String from, String to,
                                                                     int sortOrder, boolean hidden) {
        return AddWorkflowTransitionRequest.newBuilder()
                .setHeader(header())
                .setBody(AddWorkflowTransitionRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setWorkflowId(workflowId)
                        .setActorUserId(ACTOR_USER_ID.toString())
                        .setTransition(WorkflowTransitionCreateRequest.newBuilder()
                                .setFromStatusKey(from)
                                .setToStatusKey(to)
                                .setName(from + " to " + to)
                                .setSortOrder(sortOrder)
                                .setIsHidden(hidden)))
                .build();
    }
}

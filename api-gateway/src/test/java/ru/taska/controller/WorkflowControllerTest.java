package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.CreateWorkflowStatusRequestDto;
import ru.taska.domain.dto.CreateWorkflowTransitionRequestDto;
import ru.taska.domain.dto.ProjectWorkflowDto;
import ru.taska.domain.dto.ProjectWorkflowsResponseDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusCategoryDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.domain.dto.WorkflowTransitionDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;
import ru.taska.transport.grpc.GrpcProjectServiceClient;
import ru.taska.domain.dto.ProjectResponseDto;

import java.util.List;
import java.util.UUID;


@WebFluxTest(controllers = WorkflowController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class WorkflowControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000000";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ISSUE_TYPE = "BUG";
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcWorkflowServiceClient workflowClient;

    @MockitoBean
    private GrpcProjectServiceClient projectClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и WorkflowResponseDto при успешном запросе")
    void getWorkflowForProject_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var statusTodo = new WorkflowStatusDto();
        statusTodo.setId(UUID.randomUUID());

        var transition = new WorkflowTransitionDto();
        transition.setId(UUID.randomUUID());

        var response = new WorkflowResponseDto();
        response.setId(UUID.randomUUID());
        response.setStatuses(List.of(statusTodo));
        response.setTransitions(List.of(transition));

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(WorkflowResponseDto.class).isEqualTo(response);

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK с дефолтным воркфлоу (fallback)")
    void getWorkflowForProject_shouldReturn200WithDefaultWorkflow_whenServiceReturnsDefault() {
        mockAuthenticatedUser();

        var defaultWorkflowResponse = new WorkflowResponseDto();
        defaultWorkflowResponse.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        defaultWorkflowResponse.setName("Default workflow");
        defaultWorkflowResponse.setVersion(1);

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(defaultWorkflowResponse));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(WorkflowResponseDto.class)
                .isEqualTo(defaultWorkflowResponse);

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при отсутствии query параметра issueType")
    void getWorkflowForProject_shouldReturn400_whenIssueTypeIsMissing() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().is4xxClientError()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при передаче некорректного ключа issueType")
    void getWorkflowForProject_shouldReturn400_whenIssueTypeIsInvalid() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, "bad-type")
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized если заголовок Authorization отсутствует")
    void getWorkflowForProject_shouldReturn401_whenTokenIsMissing() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden если у пользователя нет прав доступа к проекту")
    void getWorkflowForProject_shouldReturn403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));


        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 404 Not Found если воркфлоу для проекта не найден")
    void getWorkflowForProject_shouldReturn404_whenWorkflowNotFound() {
        mockAuthenticatedUser();

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));


        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 503 Service Unavailable если gRPC сервис недоступен")
    void getWorkflowForProject_shouldReturn503_whenServiceIsUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.asRuntimeException()));


        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 504 Gateway Timeout если превышен deadline gRPC вызова")
    void getWorkflowForProject_shouldReturn504_whenDeadlineExceeded() {
        mockAuthenticatedUser();

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.asRuntimeException()));


        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, ISSUE_TYPE)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(workflowClient).getWorkflowForProject(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.any(GatewayContext.class));
    }


    @ParameterizedTest
    @CsvSource({"PERMISSION_DENIED,403", "NOT_FOUND,404", "UNAVAILABLE,503", "DEADLINE_EXCEEDED,504"})
    void projectAccessFailure_doesNotReadWorkflow(String code, int httpStatus) {
        mockAuthenticatedUser();
        Mockito.when(projectClient.getProject(Mockito.eq(PROJECT_ID.toString()), Mockito.any()))
                .thenReturn(Mono.error(Status.fromCode(Status.Code.valueOf(code)).asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType=BUG", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange().expectStatus().isEqualTo(httpStatus);

        Mockito.verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("GET /workflows: 200 со всеми workflow проекта после проверки доступа к проекту")
    void listProjectWorkflows_shouldReturn200_whenProjectIsVisible() {
        mockAuthenticatedUser();

        var transition = new WorkflowTransitionDto();
        transition.setId(UUID.randomUUID());
        transition.setHidden(true);
        var workflow = new WorkflowResponseDto();
        workflow.setId(UUID.randomUUID());
        workflow.setTransitions(List.of(transition));
        var entry = new ProjectWorkflowDto();
        entry.setWorkflow(workflow);
        entry.setIssueTypes(List.of("TASK", "BUG"));
        entry.setShared(true);
        var response = new ProjectWorkflowsResponseDto();
        response.setItems(List.of(entry));

        Mockito.when(workflowClient.listProjectWorkflows(Mockito.eq(PROJECT_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflows", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].shared").isEqualTo(true)
                .jsonPath("$.items[0].issueTypes[1]").isEqualTo("BUG")
                .jsonPath("$.items[0].workflow.transitions[0].hidden").isEqualTo(true);

        Mockito.verify(projectClient).getProject(Mockito.eq(PROJECT_ID.toString()), Mockito.any());
    }

    @Test
    @DisplayName("GET /workflows: проект недоступен — workflow не читаются")
    void listProjectWorkflows_shouldNotReadWorkflows_whenProjectIsNotVisible() {
        mockAuthenticatedUser();
        Mockito.when(projectClient.getProject(Mockito.eq(PROJECT_ID.toString()), Mockito.any()))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflows", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound();

        Mockito.verifyNoInteractions(workflowClient);
    }

    @Test
    @DisplayName("POST /workflows: 201 и созданный workflow")
    void createProjectWorkflow_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();
        var response = new ProjectWorkflowDto(new WorkflowResponseDto(), List.of("TASK"), false);
        response.getWorkflow().setId(UUID.randomUUID());
        response.getWorkflow().setName("Development");

        Mockito.when(workflowClient.createWorkflow(Mockito.eq(PROJECT_ID), Mockito.any(), Mockito.any(GatewayContext.class)))
                .thenAnswer(invocation -> invocation.<Mono<CreateWorkflowRequestDto>>getArgument(1).map(request -> response));

        var status = new CreateWorkflowStatusRequestDto("TODO", "To Do", WorkflowStatusCategoryDto.TODO, 10);
        var request = new CreateWorkflowRequestDto("Development", List.of("TASK"), List.of(status), List.of());

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/workflows", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProjectWorkflowDto.class).isEqualTo(response);
    }

    @Test
    @DisplayName("POST /workflows: без обязательных полей — 400")
    void createProjectWorkflow_shouldReturn400_whenRequiredFieldsAreMissing() {
        mockAuthenticatedUser();
        Mockito.when(workflowClient.createWorkflow(Mockito.eq(PROJECT_ID), Mockito.any(), Mockito.any(GatewayContext.class)))
                .thenAnswer(invocation -> invocation.<Mono<CreateWorkflowRequestDto>>getArgument(1)
                        .map(request -> new ProjectWorkflowDto()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/workflows", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\": \"Development\"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /workflows/{workflowId}/statuses: 201 и workflow целиком")
    void addWorkflowStatus_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();
        UUID workflowId = UUID.randomUUID();
        var response = new ProjectWorkflowDto(new WorkflowResponseDto(), List.of("TASK"), false);
        response.getWorkflow().setId(workflowId);
        response.getWorkflow().setVersion(2);

        Mockito.when(workflowClient.addWorkflowStatus(Mockito.eq(PROJECT_ID), Mockito.eq(workflowId), Mockito.any(),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(invocation -> invocation.<Mono<CreateWorkflowStatusRequestDto>>getArgument(2).map(request -> response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/workflows/{workflowId}/statuses", PROJECT_ID, workflowId)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkflowStatusRequestDto("CODE_REVIEW", "Code Review", WorkflowStatusCategoryDto.IN_PROGRESS, 25))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProjectWorkflowDto.class).isEqualTo(response);
    }

    @ParameterizedTest
    @CsvSource({"ALREADY_EXISTS,409", "FAILED_PRECONDITION,400", "INVALID_ARGUMENT,400", "PERMISSION_DENIED,403", "NOT_FOUND,404"})
    @DisplayName("POST /workflows/{workflowId}/statuses: ошибки workflow-service отдаются своими HTTP-статусами")
    void addWorkflowStatus_shouldMapWorkflowServiceErrors(String code, int httpStatus) {
        mockAuthenticatedUser();
        UUID workflowId = UUID.randomUUID();
        Mockito.when(workflowClient.addWorkflowStatus(Mockito.eq(PROJECT_ID), Mockito.eq(workflowId), Mockito.any(),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.fromCode(Status.Code.valueOf(code)).asRuntimeException()));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/workflows/{workflowId}/statuses", PROJECT_ID, workflowId)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkflowStatusRequestDto("DONE", "Done", WorkflowStatusCategoryDto.DONE, 30))
                .exchange()
                .expectStatus().isEqualTo(httpStatus);
    }

    @Test
    @DisplayName("POST /workflows/{workflowId}/transitions: 201 и workflow целиком")
    void addWorkflowTransition_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();
        UUID workflowId = UUID.randomUUID();
        var response = new ProjectWorkflowDto(new WorkflowResponseDto(), List.of("TASK"), false);
        response.getWorkflow().setId(workflowId);
        response.getWorkflow().setVersion(3);

        Mockito.when(workflowClient.addWorkflowTransition(Mockito.eq(PROJECT_ID), Mockito.eq(workflowId), Mockito.any(),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(invocation -> invocation.<Mono<CreateWorkflowTransitionRequestDto>>getArgument(2).map(request -> response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/workflows/{workflowId}/transitions", PROJECT_ID, workflowId)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkflowTransitionRequestDto("IN_PROGRESS", "TODO", "Stop Progress", 40))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ProjectWorkflowDto.class).isEqualTo(response);
    }

    private void mockAuthenticatedUser() {
        Mockito.when(projectClient.getProject(Mockito.eq(PROJECT_ID.toString()), Mockito.any()))
                .thenReturn(Mono.just(new ProjectResponseDto()));
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(USER_ID).build())
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}

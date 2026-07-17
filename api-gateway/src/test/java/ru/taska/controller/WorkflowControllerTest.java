package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import ru.taska.domain.*;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
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

import java.util.List;
import java.util.UUID;

import static io.grpc.Status.*;

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
    private static final IssueTypeDto ISSUE_TYPE = IssueTypeDto.BUG;
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcWorkflowServiceClient workflowClient;

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
    @DisplayName("Должен вернуть 400 Bad Request при передаче неизвестного issueType")
    void getWorkflowForProject_shouldReturn400_whenIssueTypeIsInvalid() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/workflow?issueType={issueType}", PROJECT_ID, "BAD_ISSUE_TYPE")
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
                .thenReturn(Mono.error(PERMISSION_DENIED.asRuntimeException()));


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
                .thenReturn(Mono.error(NOT_FOUND.asRuntimeException()));


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
                .thenReturn(Mono.error(UNAVAILABLE.asRuntimeException()));


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
                .thenReturn(Mono.error(DEADLINE_EXCEEDED.asRuntimeException()));


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


    private void mockAuthenticatedUser() {
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

package ru.taska.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.mapper.IssueMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcIssueServiceClient;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.List;
import java.util.UUID;

@WebFluxTest(controllers = BoardController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
@DisplayName("BoardController WebTestClient Tests")
class BoardControllerWebTestClientTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000000";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final IssueTypeDto ISSUE_TYPE = IssueTypeDto.TASK;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcWorkflowServiceClient workflowClient;

    @MockitoBean
    private GrpcIssueServiceClient issueClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @MockitoBean
    private IssueMapper issueMapper; // <-- Добавили мок для маппера

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и доску с задачами и пустыми колонками")
    void getBoard_shouldReturn200_andGroupedBoard() {
        mockAuthenticatedUser();

        // 1. Мокаем Workflow (возвращаем статус колонкам!)
        var statusTodo = new WorkflowStatusDto();
        statusTodo.setId(UUID.randomUUID());
        statusTodo.setStatusKey("TODO"); // <-- Вернули
        statusTodo.setSortOrder(1);

        var statusDone = new WorkflowStatusDto();
        statusDone.setId(UUID.randomUUID());
        statusDone.setStatusKey("DONE"); // <-- Вернули
        statusDone.setSortOrder(2);

        var workflowResponse = new WorkflowResponseDto();
        workflowResponse.setStatuses(List.of(statusTodo, statusDone));

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(workflowResponse));

        // 2. Мокаем Issue Service (возвращаем gRPC объект)
        var grpcIssueId = UUID.randomUUID();
        var grpcIssue = ru.taska.api.issue.v1.BoardIssue.newBuilder()
                .setId(grpcIssueId.toString())
                .setIssueKey("TAS-1")
                .setStatusKey("TODO")
                .build();

        var restIssue = new BoardIssueDto();
        restIssue.setId(grpcIssueId);
        restIssue.setIssueKey("TAS-1");

        Mockito.when(issueMapper.toRestBoardIssue(Mockito.any())).thenReturn(restIssue);

        Mockito.when(issueClient.listIssuesForBoard(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(ISSUE_TYPE.name()),
                        Mockito.any(), // assigneeId
                        Mockito.any(), // labelId
                        Mockito.any(), // includeDone
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(List.of(grpcIssue)));

        // 3. Вызываем API и проверяем результат
        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.projectId").isEqualTo(PROJECT_ID.toString())
                .jsonPath("$.issueType").isEqualTo(ISSUE_TYPE.name())
                .jsonPath("$.columns").isArray()
                .jsonPath("$.columns.length()").isEqualTo(2)
                .jsonPath("$.columns[0].statusKey").isEqualTo("TODO")
                .jsonPath("$.columns[0].issues.length()").isEqualTo(1)
                .jsonPath("$.columns[0].issues[0].issueKey").isEqualTo("TAS-1")
                .jsonPath("$.columns[1].statusKey").isEqualTo("DONE")
                .jsonPath("$.columns[1].issues.length()").isEqualTo(0);

        Mockito.verify(workflowClient).getWorkflowForProject(Mockito.eq(PROJECT_ID), Mockito.eq(ISSUE_TYPE), Mockito.any());
        Mockito.verify(issueClient).listIssuesForBoard(Mockito.eq(PROJECT_ID.toString()), Mockito.eq(ISSUE_TYPE.name()), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть 500 Internal Server Error если статус задачи отсутствует в workflow")
    void getBoard_shouldReturn500_whenIssueHasUnknownStatus() {
        mockAuthenticatedUser();

        var statusTodo = new WorkflowStatusDto();
        statusTodo.setId(UUID.randomUUID());
        statusTodo.setStatusKey("TODO"); // <-- Вернули
        statusTodo.setSortOrder(1);

        var workflowResponse = new WorkflowResponseDto();
        workflowResponse.setStatuses(List.of(statusTodo));

        Mockito.when(workflowClient.getWorkflowForProject(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(workflowResponse));

        // Создаем задачу со статусом, которого нет в workflow
        var grpcIssue = ru.taska.api.issue.v1.BoardIssue.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setIssueKey("TAS-1")
                .setStatusKey("UNKNOWN_STATUS")
                .build();

        Mockito.when(issueClient.listIssuesForBoard(
                        Mockito.eq(PROJECT_ID.toString()),
                        Mockito.eq(ISSUE_TYPE.name()),
                        Mockito.any(), // assigneeId
                        Mockito.any(), // labelId
                        Mockito.any(), // includeDone
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(List.of(grpcIssue)));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized если заголовок Authorization отсутствует")
    void getBoard_shouldReturn401_whenTokenIsMissing() {
        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(workflowClient, issueClient);
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
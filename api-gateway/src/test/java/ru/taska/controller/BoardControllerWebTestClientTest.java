package ru.taska.controller;

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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.BoardColumnDto;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.BoardResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.service.BoardService;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

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
    private ContextMapper contextMapper;

    @MockitoBean
    private BoardService boardService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и доску с задачами и пустыми колонками")
    void getBoard_shouldReturn200_andGroupedBoard() {
        mockAuthenticatedUser();

        var issue = new BoardIssueDto();
        issue.setId(UUID.randomUUID());
        issue.setIssueKey("TAS-1");

        var todoColumn = new BoardColumnDto();
        todoColumn.setStatusKey("TODO");
        todoColumn.setSortOrder(1);
        todoColumn.setIssues(List.of(issue));

        var doneColumn = new BoardColumnDto();
        doneColumn.setStatusKey("DONE");
        doneColumn.setSortOrder(2);
        doneColumn.setIssues(List.of());

        var response = new BoardResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setIssueType(ISSUE_TYPE);
        response.setColumns(List.of(todoColumn, doneColumn));

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectBody(String.class)
                .consumeWith(result -> {
                    System.out.println("Status: " + result.getStatus());
                    System.out.println("Body: " + result.getResponseBody());
                });
        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.any(GatewayContext.class)
        );
        Mockito.verifyNoMoreInteractions(boardService);
    }

    @Test
    @DisplayName("Должен вернуть 500 Internal Server Error если статус задачи отсутствует в workflow")
    void getBoard_shouldReturn500_whenIssueHasUnknownStatus() {
        mockAuthenticatedUser();

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Inconsistent state: issues found with statuses not present in workflow"
                )));

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

        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.any(GatewayContext.class)
        );
        Mockito.verifyNoMoreInteractions(boardService);
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

        Mockito.verifyNoInteractions(boardService);
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

        Mockito.when(authClient.validateAccessToken(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}
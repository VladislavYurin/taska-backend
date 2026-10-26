package ru.taska.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import ru.taska.domain.dto.BoardColumnDto;
import ru.taska.domain.dto.BoardIssueDto;
import ru.taska.domain.dto.IssuePriorityDto;
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
    private static final String ISSUE_TYPE = "TASK";

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
        response.setColumns(List.of(todoColumn, doneColumn));
        response.setUnplacedIssues(List.of());

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.projectId").isEqualTo(PROJECT_ID.toString())
                .jsonPath("$.columns.length()").isEqualTo(2)
                .jsonPath("$.columns[0].statusKey").isEqualTo("TODO")
                .jsonPath("$.columns[0].issues.length()").isEqualTo(1)
                .jsonPath("$.columns[0].issues[0].issueKey").isEqualTo("TAS-1")
                .jsonPath("$.columns[1].statusKey").isEqualTo("DONE")
                .jsonPath("$.columns[1].issues.length()").isEqualTo(0)
                .jsonPath("$.unplacedIssues.length()").isEqualTo(0);

        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.any(GatewayContext.class)
        );
        Mockito.verifyNoMoreInteractions(boardService);
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и unplacedIssues, если статус задачи отсутствует в workflow")
    void getBoard_shouldReturn200_withUnplacedIssues_whenIssueHasUnknownStatus() {
        mockAuthenticatedUser();

        var unplacedIssue = new BoardIssueDto();
        unplacedIssue.setId(UUID.randomUUID());
        unplacedIssue.setIssueKey("TAS-99");

        var response = new BoardResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setColumns(List.of());
        response.setUnplacedIssues(List.of(unplacedIssue));

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unplacedIssues.length()").isEqualTo(1)
                .jsonPath("$.unplacedIssues[0].issueKey").isEqualTo("TAS-99");

        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.any(GatewayContext.class)
        );
        Mockito.verifyNoMoreInteractions(boardService);
    }

    @Test
    @DisplayName("Должен вернуть 200 OK, если issueType не передан (все типы)")
    void getBoard_shouldReturn200_whenIssueTypeIsAbsent() {
        mockAuthenticatedUser();

        var response = new BoardResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setColumns(List.of());
        response.setUnplacedIssues(List.of());

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board").build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.projectId").isEqualTo(PROJECT_ID.toString());

        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.any(GatewayContext.class)
        );
        Mockito.verifyNoMoreInteractions(boardService);
    }

    @Test
    @DisplayName("Должен пробросить фильтры priority и query в BoardService")
    void getBoard_shouldPassPriorityAndQueryFilters() {
        mockAuthenticatedUser();

        var response = new BoardResponseDto();
        response.setProjectId(PROJECT_ID);
        response.setColumns(List.of());
        response.setUnplacedIssues(List.of());

        Mockito.when(boardService.getBoard(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(ISSUE_TYPE),
                        Mockito.isNull(),
                        Mockito.isNull(),
                        Mockito.eq(false),
                        Mockito.eq(IssuePriorityDto.HIGH),
                        Mockito.eq("auth bug"),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder.path("/api/v1/projects/{projectId}/board")
                        .queryParam("issueType", ISSUE_TYPE)
                        .queryParam("priority", IssuePriorityDto.HIGH)
                        .queryParam("query", "auth bug")
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk();

        Mockito.verify(boardService).getBoard(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(ISSUE_TYPE),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.eq(false),
                Mockito.eq(IssuePriorityDto.HIGH),
                Mockito.eq("auth bug"),
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
package ru.taska.controller;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.AssignIssueRequestDto;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.IssueResponseDto;
import ru.taska.domain.dto.IssueWithHistoryResponseDto;
import ru.taska.domain.dto.ListIssuesResponseDto;
import ru.taska.domain.dto.TransitionIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueRequestDto;
import ru.taska.domain.dto.UpdateIssueResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcIssueServiceClient;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

@WebFluxTest(controllers = IssueController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class IssueControllerTest {

    private static final String LOGIN = "test-login";
    private static final String EMAIL = "test@example.com";
    private static final String DISPLAY_NAME = "Ivan Ivanov";
    private static final String TOKEN = "Bearer JWT-token";
    private static final String IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000000";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String ASSIGNEE_ID = "00000000-0000-0000-0000-000000000002";
    private static final String ISSUE_ID = "00000000-0000-0000-0000-000000000003";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000004";
    private static final String TRANSITION_ID = "00000000-0000-0000-0000-000000000004";
    private static final String SUMMARY = "Summary-1";
    private static final String DESCRIPTION = "Description-1";
    private static final String STATUS_TODO = "TODO";
    private static final String ISSUE_TYPE_TASK = "TASK";
    private static final String ISSUE_PRIORITY_MEDIUM = "MEDIUM";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcIssueServiceClient issueClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом IssueResponseDto и статусом 201")
    void createIssue_shouldReturnsResponseAndStatus201() {
        mockAuthenticatedUser();

        var request = new CreateIssueRequestDto(ISSUE_TYPE_TASK, SUMMARY, ISSUE_PRIORITY_MEDIUM);

        var response = new IssueResponseDto();
        response.setId(ISSUE_ID);
        response.setProjectId(PROJECT_ID);
        response.setSummary(SUMMARY);

        Mockito.when(issueClient.createIssue(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(IDEMPOTENCY_KEY),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(IssueResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient).createIssue(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(IDEMPOTENCY_KEY),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен успешно обработать повторный запрос с тем же ключом идемпотентности и теми же данными")
    void createIssue_shouldReturnsSameResponse_whenSameIdempotencyKeyAndPayload() {
        mockAuthenticatedUser();

        var request = new CreateIssueRequestDto(ISSUE_TYPE_TASK, SUMMARY, ISSUE_PRIORITY_MEDIUM);

        var response = new IssueResponseDto();
        response.setId(ISSUE_ID);
        response.setProjectId(PROJECT_ID);
        response.setSummary(SUMMARY);

        Mockito.when(issueClient.createIssue(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(IDEMPOTENCY_KEY),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IssueResponseDto.class).isEqualTo(response);

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IssueResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient, Mockito.times(2)).createIssue(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(IDEMPOTENCY_KEY),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 409 Conflict при повторном ключе идемпотентности, но другими данными")
    void createIssue_shouldThrowsExceptionAndStatus409_whenSameIdempotencyKeyAndDifferentPayload() {
        mockAuthenticatedUser();

        var request = new CreateIssueRequestDto(ISSUE_TYPE_TASK, SUMMARY, ISSUE_PRIORITY_MEDIUM);

        Mockito.when(issueClient.createIssue(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(IDEMPOTENCY_KEY),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency conflict")));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(issueClient).createIssue(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(IDEMPOTENCY_KEY),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 400 Bad Request, если не передан заголовок 'Idempotency-Key'")
    void createIssue_shouldThrowsExceptionAndStatus400_whenIdempotenceKeyIsMissing() {
        mockAuthenticatedUser();

        var request = new CreateIssueRequestDto(ISSUE_TYPE_TASK, SUMMARY, ISSUE_PRIORITY_MEDIUM);

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues", PROJECT_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(issueClient, Mockito.never())
                .createIssue(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом IssueWithHistoryResponseDto и статусом 200")
    void getIssue_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new IssueWithHistoryResponseDto();

        Mockito.when(issueClient.getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(IssueWithHistoryResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient)
                .getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 404 NotFound, если задача не найдена")
    void getIssue_shouldThrowsExceptionAndStatus404_whenIssueNotFound() {
        mockAuthenticatedUser();

        Mockito.when(issueClient.getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not Found")));

        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(issueClient)
                .getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 403 Forbidden, если у пользователя нет прав")
    void getIssue_shouldThrowsExceptionAndStatus403_whenPermissionDenied() {
        mockAuthenticatedUser();

        Mockito.when(issueClient.getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access Denied")));

        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(issueClient)
                .getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 401 Unauthorized если нет токена")
    void getIssue_shouldThrowsExceptionAndStatus401_whenJwtTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(issueClient, Mockito.never())
                .getIssue(Mockito.any(), Mockito.any());
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 503 Unavailable если downstream недоступен")
    void getIssue_shouldThrowsExceptionAndStatus503_whenDownstreamUnavailable() {
        mockAuthenticatedUser();

        Mockito.when(issueClient.getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.UNAVAILABLE.withDescription("Service Unavailable").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен выбросить исключение со статусом 504 Deadline exceeded если downstream превысил deadline")
    void getIssue_shouldThrowsExceptionAndStatus504_whenDeadlineExceeded() {
        mockAuthenticatedUser();

        Mockito.when(issueClient.getIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.DEADLINE_EXCEEDED.withDescription("Timeout Exceeded").asRuntimeException()));

        webTestClient.get()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом ListIssuesResponseDto и статусом 200")
    void listIssues_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var response = new ListIssuesResponseDto();

        Mockito.when(issueClient.listIssues(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(STATUS_TODO),
                        Mockito.eq(ASSIGNEE_ID),
                        Mockito.eq(0),
                        Mockito.eq(5),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> builder
                        .path("/api/v1/projects/{projectId}/issues")
                        .queryParam("status", STATUS_TODO)
                        .queryParam("assigneeId", ASSIGNEE_ID)
                        .queryParam("page", 0)
                        .queryParam("pageSize", 5)
                        .build(PROJECT_ID))
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(ListIssuesResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient).listIssues(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(STATUS_TODO),
                Mockito.eq(ASSIGNEE_ID),
                Mockito.eq(0),
                Mockito.eq(5),
                Mockito.any(GatewayContext.class)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listIssuesArguments")
    @DisplayName("Должен корректно обрабатывать все фильтры")
    void listIssues_shouldPassAllFilters(
            String description,
            Consumer<UriBuilder> uriConfigurer,
            String expectedStatus,
            String expectedAssigneeId,
            Integer expectedPage,
            Integer expectedPageSize
    ) {
        mockAuthenticatedUser();

        var response = new ListIssuesResponseDto();

        Mockito.when(issueClient.listIssues(
                        Mockito.eq(PROJECT_ID),
                        Mockito.eq(expectedStatus),
                        Mockito.eq(expectedAssigneeId),
                        Mockito.eq(expectedPage),
                        Mockito.eq(expectedPageSize),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(builder -> {
                    builder.path("/api/v1/projects/{projectId}/issues");
                    uriConfigurer.accept(builder);
                    return builder.build(PROJECT_ID);
                })
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(ListIssuesResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient).listIssues(
                Mockito.eq(PROJECT_ID),
                Mockito.eq(expectedStatus),
                Mockito.eq(expectedAssigneeId),
                Mockito.eq(expectedPage),
                Mockito.eq(expectedPageSize),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом UpdateIssueResponseDto и статусом 200")
    void updateIssue_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var request = new UpdateIssueRequestDto();
        request.setSummary(SUMMARY);
        request.setDescription(DESCRIPTION);
        request.setPriority(ISSUE_PRIORITY_MEDIUM);

        var response = new UpdateIssueResponseDto();
        response.setId(ISSUE_ID);
        response.setSummary(SUMMARY);
        response.setDescription(DESCRIPTION);
        response.setPriority(ISSUE_PRIORITY_MEDIUM);

        Mockito.when(issueClient.updateIssue(
                        Mockito.eq(ISSUE_ID),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(UpdateIssueResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient)
                .updateIssue(Mockito.eq(ISSUE_ID), Mockito.any(Mono.class), Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом IssueResponseDto и статусом 200")
    void assignIssue_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var request = new AssignIssueRequestDto(ASSIGNEE_ID);

        var response = new IssueResponseDto();
        response.setId(USER_ID);
        response.setAssigneeId(ASSIGNEE_ID);

        Mockito.when(issueClient.assignIssue(
                        Mockito.eq(ISSUE_ID),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri("/api/v1/issues/{issueId}/assignee", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(IssueResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient)
                .assignIssue(Mockito.eq(ISSUE_ID), Mockito.any(Mono.class), Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть ответ с телом IssueWithHistoryResponseDto и статусом 200")
    void transitionIssue_shouldReturnsResponseAndStatus200() {
        mockAuthenticatedUser();

        var request = new TransitionIssueRequestDto();
        request.setPayload(Map.of("oldStatus", "TODO"));

        var response = new IssueWithHistoryResponseDto();

        Mockito.when(issueClient.transitionIssue(
                        Mockito.eq(ISSUE_ID),
                        Mockito.eq(TRANSITION_ID),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri("/api/v1/issues/{issueId}/transition/{transitionId}", ISSUE_ID, TRANSITION_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(IssueWithHistoryResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient).transitionIssue(
                Mockito.eq(ISSUE_ID),
                Mockito.eq(TRANSITION_ID),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен вернуть корректный ответ, если payload не передан")
    void transitionIssue_shouldReturnsResponse_whenPayloadAbsent() {
        mockAuthenticatedUser();

        var response = new IssueWithHistoryResponseDto();

        Mockito.when(issueClient.transitionIssue(
                        Mockito.eq(ISSUE_ID),
                        Mockito.eq(TRANSITION_ID),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri("/api/v1/issues/{issueId}/transition/{transitionId}", ISSUE_ID, TRANSITION_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody(IssueWithHistoryResponseDto.class).isEqualTo(response);

        Mockito.verify(issueClient).transitionIssue(
                Mockito.eq(ISSUE_ID),
                Mockito.eq(TRANSITION_ID),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class)
        );
    }

    @Test
    @DisplayName("Должен вернуть ответ без тела со статусом 204")
    void deleteIssue_shouldReturnsNoContentAndStatus204() {
        mockAuthenticatedUser();

        Mockito.when(issueClient.deleteIssue(
                        Mockito.eq(ISSUE_ID),
                        Mockito.any(GatewayContext.class)
                ))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri("/api/v1/issues/{issueId}", ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(issueClient).deleteIssue(Mockito.eq(ISSUE_ID), Mockito.any(GatewayContext.class));
    }

    private static Stream<Arguments> listIssuesArguments() {
        return Stream.of(
                Arguments.of(
                        "без фильтров",
                        (Consumer<UriBuilder>) builder -> {
                        },
                        null,
                        null,
                        null,
                        null
                ),
                Arguments.of(
                        "status",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("status", STATUS_TODO),
                        STATUS_TODO,
                        null,
                        null,
                        null
                ),
                Arguments.of(
                        "assigneeId",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("assigneeId", ASSIGNEE_ID),
                        null,
                        ASSIGNEE_ID,
                        null,
                        null
                ),
                Arguments.of(
                        "page/pageSize",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("page", 0)
                                .queryParam("pageSize", 5),
                        null,
                        null,
                        0,
                        5
                ),
                Arguments.of(
                        "все фильтры",
                        (Consumer<UriBuilder>) builder -> builder
                                .queryParam("status", STATUS_TODO)
                                .queryParam("assigneeId", ASSIGNEE_ID)
                                .queryParam("page", 0)
                                .queryParam("pageSize", 5),
                        STATUS_TODO,
                        ASSIGNEE_ID,
                        0,
                        5
                )
        );
    }

    private void mockAuthenticatedUser() {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(
                        UserContext.newBuilder()
                                .setUserId(USER_ID)
                                .build()
                )
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .login(LOGIN)
                .email(EMAIL)
                .displayName(DISPLAY_NAME)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(GlobalRole.USER)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }
}
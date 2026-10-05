package ru.taska.controller;

import io.grpc.Status;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import ru.taska.domain.dto.AddIssueWorklogRequestDto;
import ru.taska.domain.dto.IssueWorklogResponseDto;
import ru.taska.domain.dto.ListIssueWorklogsResponseDto;
import ru.taska.domain.dto.UpdateIssueWorklogRequestDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcIssueWorklogServiceClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * WebTestClient-тесты для {@link WorklogController}.
 * Реальный security/executor-слой поднимается через {@code @Import},
 * а gRPC-клиент worklogs мокается.
 */
@WebFluxTest(controllers = WorklogController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class WorklogControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WORKLOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static final String WORKLOGS_URI = "/api/v1/projects/{projectId}/issues/{issueId}/worklogs";
    private static final String WORKLOG_URI = "/api/v1/projects/{projectId}/issues/{issueId}/worklogs/{worklogId}";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcIssueWorklogServiceClient worklogClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    // ============ GET /worklogs ============

    @Test
    @DisplayName("listIssueWorklogs: должен вернуть 200 OK и список worklogs")
    void listIssueWorklogs_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = new ListIssueWorklogsResponseDto();
        response.setItems(List.of(worklogDto()));

        Mockito.when(worklogClient.listIssueWorklogs(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ListIssueWorklogsResponseDto.class)
                .isEqualTo(response);
    }

    @Test
    @DisplayName("listIssueWorklogs: должен вернуть 401 Unauthorized при отсутствии токена")
    void listIssueWorklogs_shouldReturn401_whenTokenMissing() {
        webTestClient.get()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(worklogClient);
    }

    @Test
    @DisplayName("listIssueWorklogs: должен вернуть 403 Forbidden, если у пользователя нет доступа к задаче")
    void listIssueWorklogs_shouldReturn403_whenUserHasNoAccess() {
        mockAuthenticatedUser();

        Mockito.when(worklogClient.listIssueWorklogs(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.get()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("listIssueWorklogs: должен вернуть 404 Not Found, если задача не найдена")
    void listIssueWorklogs_shouldReturn404_whenIssueNotFound() {
        mockAuthenticatedUser();

        Mockito.when(worklogClient.listIssueWorklogs(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.get()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    // ============ POST /worklogs ============

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 201 Created и созданный worklog, actor берётся из Gateway context")
    void addIssueWorklog_shouldReturn201_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = new AddIssueWorklogRequestDto(120, LocalDate.parse("2026-07-13"))
                .comment("Implemented validation");
        var response = worklogDto();

        Mockito.when(worklogClient.addIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(IssueWorklogResponseDto.class)
                .isEqualTo(response);

        ArgumentCaptor<GatewayContext> contextCaptor = ArgumentCaptor.forClass(GatewayContext.class);
        Mockito.verify(worklogClient).addIssueWorklog(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                contextCaptor.capture());

        Assertions.assertThat(contextCaptor.getValue().userContext().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request при spentMinutes = 0")
    void addIssueWorklog_shouldReturn400_whenSpentMinutesIsZero() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"spentMinutes\": 0, \"workDate\": \"2026-07-13\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request при отрицательном spentMinutes")
    void addIssueWorklog_shouldReturn400_whenSpentMinutesIsNegative() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"spentMinutes\": -10, \"workDate\": \"2026-07-13\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request при отсутствии обязательных полей")
    void addIssueWorklog_shouldReturn400_whenRequiredFieldsAreMissing() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request при невалидной дате")
    void addIssueWorklog_shouldReturn400_whenWorkDateIsInvalid() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"spentMinutes\": 60, \"workDate\": \"2026-13-45\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request, если comment длиннее 2000 символов")
    void addIssueWorklog_shouldReturn400_whenCommentIsTooLong() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        var request = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"))
                .comment("a".repeat(2001));

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 400 Bad Request при отсутствующем теле запроса")
    void addIssueWorklog_shouldReturn400_whenBodyIsMissing() {
        mockAuthenticatedUser();
        mockAddWorklogConsumingBody();

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 401 Unauthorized при отсутствии токена")
    void addIssueWorklog_shouldReturn401_whenTokenMissing() {
        var request = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"));

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verifyNoInteractions(worklogClient);
    }

    @Test
    @DisplayName("addIssueWorklog: должен вернуть 404 Not Found, если задача не найдена")
    void addIssueWorklog_shouldReturn404_whenIssueNotFound() {
        mockAuthenticatedUser();

        var request = new AddIssueWorklogRequestDto(60, LocalDate.parse("2026-07-13"));

        Mockito.when(worklogClient.addIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.post()
                .uri(WORKLOGS_URI, PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    // ============ PUT /worklogs/{worklogId} ============

    @Test
    @DisplayName("updateIssueWorklog: должен вернуть 200 OK и обновлённый worklog")
    void updateIssueWorklog_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = new UpdateIssueWorklogRequestDto();
        request.setSpentMinutes(90);

        var response = worklogDto();
        response.setSpentMinutes(90);
        response.setUpdatedAt(OffsetDateTime.parse("2026-07-13T11:00:00Z"));

        Mockito.when(worklogClient.updateIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(WORKLOG_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(IssueWorklogResponseDto.class)
                .isEqualTo(response);
    }

    @Test
    @DisplayName("updateIssueWorklog: должен вернуть 400 Bad Request при spentMinutes = 0")
    void updateIssueWorklog_shouldReturn400_whenSpentMinutesIsZero() {
        mockAuthenticatedUser();

        Mockito.when(worklogClient.updateIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(WORKLOG_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(inv -> {
                    Mono<UpdateIssueWorklogRequestDto> body = inv.getArgument(2);
                    return body.map(dto -> worklogDto());
                });

        webTestClient.put()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"spentMinutes\": 0}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("updateIssueWorklog: должен вернуть 403 Forbidden при попытке изменить чужой worklog")
    void updateIssueWorklog_shouldReturn403_whenUserIsNotAuthor() {
        mockAuthenticatedUser();

        var request = new UpdateIssueWorklogRequestDto();
        request.setSpentMinutes(90);

        Mockito.when(worklogClient.updateIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(WORKLOG_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.put()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("updateIssueWorklog: должен вернуть 401 Unauthorized при отсутствии токена")
    void updateIssueWorklog_shouldReturn401_whenTokenMissing() {
        webTestClient.put()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"spentMinutes\": 90}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verifyNoInteractions(worklogClient);
    }

    // ============ DELETE /worklogs/{worklogId} ============

    @Test
    @DisplayName("deleteIssueWorklog: должен вернуть 204 No Content")
    void deleteIssueWorklog_shouldReturn204_whenRequestIsValid() {
        mockAuthenticatedUser();

        Mockito.when(worklogClient.deleteIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(WORKLOG_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.empty());

        webTestClient.delete()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().exists("X-Request-Id")
                .expectBody().isEmpty();

        Mockito.verify(worklogClient).deleteIssueWorklog(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.eq(WORKLOG_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("deleteIssueWorklog: должен вернуть 404 Not Found, если worklog не найден")
    void deleteIssueWorklog_shouldReturn404_whenWorklogNotFound() {
        mockAuthenticatedUser();

        Mockito.when(worklogClient.deleteIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(WORKLOG_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.NOT_FOUND.asRuntimeException()));

        webTestClient.delete()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Request-Id");
    }

    @Test
    @DisplayName("deleteIssueWorklog: должен вернуть 401 Unauthorized при отсутствии токена")
    void deleteIssueWorklog_shouldReturn401_whenTokenMissing() {
        webTestClient.delete()
                .uri(WORKLOG_URI, PROJECT_ID, ISSUE_ID, WORKLOG_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");

        Mockito.verifyNoInteractions(worklogClient);
    }

    // ============ helpers ============

    /**
     * Мок клиента, который подписывается на тело запроса.
     * Валидация {@code @Valid Mono<Dto>} срабатывает только при подписке,
     * поэтому без этого невалидное тело не дойдёт до проверки.
     */
    private void mockAddWorklogConsumingBody() {
        Mockito.when(worklogClient.addIssueWorklog(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(inv -> {
                    Mono<AddIssueWorklogRequestDto> body = inv.getArgument(1);
                    return body.map(dto -> worklogDto());
                });
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

    private IssueWorklogResponseDto worklogDto() {
        var dto = new IssueWorklogResponseDto();
        dto.setId(WORKLOG_ID);
        dto.setIssueId(ISSUE_ID);
        dto.setProjectId(PROJECT_ID);
        dto.setAuthorUserId(UUID.fromString(USER_ID));
        dto.setSpentMinutes(120);
        dto.setWorkDate(LocalDate.parse("2026-07-13"));
        dto.setComment("Implemented validation");
        dto.setCreatedAt(OffsetDateTime.parse("2026-07-13T10:00:00Z"));
        return dto;
    }
}
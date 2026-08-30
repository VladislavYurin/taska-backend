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
import ru.taska.domain.dto.AddIssueWatcherRequestDto;
import ru.taska.domain.dto.IssueWatcherResponseDto;
import ru.taska.domain.dto.ListIssueWatchersResponseDto;
import ru.taska.domain.dto.UnwatchIssueResponseDto;
import ru.taska.domain.dto.WatchIssueResponseDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcIssueWatcherServiceClient;

import java.util.List;
import java.util.UUID;

@WebFluxTest(controllers = WatchersController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
class WatchersControllerTest {

    private static final String TOKEN = "Bearer JWT-token";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ISSUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WATCHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID TARGET_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private GatewayContextFactory contextFactory;

    @MockitoBean
    private GrpcAuthServiceClient authClient;

    @MockitoBean
    private GrpcIssueWatcherServiceClient watcherClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("Должен вернуть 200 OK и список подписчиков задачи")
    void listIssueWatchers_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var watcher = issueWatcherResponse(TARGET_USER_ID);
        var response = new ListIssueWatchersResponseDto();
        response.setWatchers(List.of(watcher));
        response.setTotalCount(1);

        Mockito.when(watcherClient.listIssueWatchers(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(ListIssueWatchersResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(watcherClient).listIssueWatchers(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK при подписке текущего пользователя")
    void watchIssueMe_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = watchIssueResponse(UUID.fromString(USER_ID), 3);

        Mockito.when(watcherClient.watchIssueMe(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.put()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers/me", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(WatchIssueResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(watcherClient).watchIssueMe(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK при отписке текущего пользователя")
    void unwatchIssueMe_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = unwatchIssueResponse(true, 2);

        Mockito.when(watcherClient.unwatchIssueMe(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers/me", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(UnwatchIssueResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(watcherClient).unwatchIssueMe(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK при добавлении подписчика на задачу")
    void addIssueWatcher_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var request = addWatcherRequest(TARGET_USER_ID);
        var response = watchIssueResponse(TARGET_USER_ID, 4);

        Mockito.when(watcherClient.addIssueWatcher(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(WatchIssueResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(watcherClient).addIssueWatcher(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.any(Mono.class),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 200 OK при удалении подписчика задачи")
    void removeIssueWatcher_shouldReturn200_whenRequestIsValid() {
        mockAuthenticatedUser();

        var response = unwatchIssueResponse(true, 3);

        Mockito.when(watcherClient.removeIssueWatcher(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(TARGET_USER_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.just(response));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers/{userId}", PROJECT_ID, ISSUE_ID, TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(UnwatchIssueResponseDto.class)
                .isEqualTo(response);

        Mockito.verify(watcherClient).removeIssueWatcher(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.eq(TARGET_USER_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 403 Forbidden при удалении подписчика без прав")
    void removeIssueWatcher_shouldReturn403_whenUserHasNoPermission() {
        mockAuthenticatedUser();

        Mockito.when(watcherClient.removeIssueWatcher(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.eq(TARGET_USER_ID.toString()),
                        Mockito.any(GatewayContext.class)))
                .thenReturn(Mono.error(Status.PERMISSION_DENIED.asRuntimeException()));

        webTestClient.delete()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers/{userId}", PROJECT_ID, ISSUE_ID, TARGET_USER_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verify(watcherClient).removeIssueWatcher(
                Mockito.eq(ISSUE_ID.toString()),
                Mockito.eq(TARGET_USER_ID.toString()),
                Mockito.any(GatewayContext.class));
    }

    @Test
    @DisplayName("Должен вернуть 400 Bad Request при отсутствующем или неполном теле запроса")
    void addIssueWatcher_shouldReturn400_whenBodyIsMissingOrInvalid() {
        mockAuthenticatedUser();

        Mockito.when(watcherClient.addIssueWatcher(
                        Mockito.eq(ISSUE_ID.toString()),
                        Mockito.any(Mono.class),
                        Mockito.any(GatewayContext.class)))
                .thenAnswer(inv -> {
                    Mono<AddIssueWatcherRequestDto> body = inv.getArgument(1);
                    return body.map(dto -> watchIssueResponse(dto.getUserId(), 1));
                });

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isBadRequest();

        webTestClient.post()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers", PROJECT_ID, ISSUE_ID)
                .header(HttpHeaders.AUTHORIZATION, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Должен вернуть 401 Unauthorized при отсутствии токена")
    void listIssueWatchers_shouldReturn401_whenTokenMissing() {
        webTestClient.get()
                .uri("/api/v1/projects/{projectId}/issues/{issueId}/watchers", PROJECT_ID, ISSUE_ID)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.code").exists()
                .jsonPath("$.message").exists();

        Mockito.verifyNoInteractions(watcherClient);
    }

    private void mockAuthenticatedUser() {
        mockAuthenticatedUserWithRole(GlobalRole.USER);
    }

    private void mockAuthenticatedUserWithRole(GlobalRole role) {
        var accessToken = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder().setUserId(USER_ID).build())
                .build();

        var userContext = GatewayUserContext.builder()
                .userId(USER_ID)
                .status(GatewayUserStatus.ACTIVE)
                .globalRole(role)
                .build();

        Mockito.when(authClient.validateAccessToken(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Mono.just(accessToken));

        Mockito.when(contextMapper.mapToGatewayUserContext(Mockito.any(UserContext.class)))
                .thenReturn(userContext);
    }

    private AddIssueWatcherRequestDto addWatcherRequest(UUID targetUserId) {
        var request = new AddIssueWatcherRequestDto();
        request.setUserId(targetUserId);
        return request;
    }

    private IssueWatcherResponseDto issueWatcherResponse(UUID watcherUserId) {
        var response = new IssueWatcherResponseDto();
        response.setId(WATCHER_ID);
        response.setIssueId(ISSUE_ID);
        response.setProjectId(PROJECT_ID);
        response.setUserId(watcherUserId);
        response.setCreatedBy(UUID.fromString(USER_ID));
        return response;
    }

    private WatchIssueResponseDto watchIssueResponse(UUID watcherUserId, Integer count) {
        var response = new WatchIssueResponseDto();
        response.setWatcher(issueWatcherResponse(watcherUserId));
        response.setWatchersCount(count);
        return response;
    }

    private UnwatchIssueResponseDto unwatchIssueResponse(Boolean removed, Integer count) {
        var response = new UnwatchIssueResponseDto();
        response.setIssueId(ISSUE_ID);
        response.setRemoved(removed);
        response.setWatchersCount(count);
        return response;
    }
}

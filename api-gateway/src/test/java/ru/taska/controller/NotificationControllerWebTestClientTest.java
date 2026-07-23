package ru.taska.controller;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.taska.api.auth.v1.ValidateAccessTokenResponse;
import ru.taska.api.common.v1.UserContext;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.error.GatewayValidationExceptionHandler;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.domain.dto.NotificationTypeDto;
import ru.taska.error.GatewayErrorHandler;
import ru.taska.error.RestErrorMapper;
import ru.taska.filter.BearerTokenExtractor;
import ru.taska.filter.GatewayContextFactory;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.filter.RequestIdProvider;
import ru.taska.mapper.ContextMapper;
import ru.taska.mapper.NotificationMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;
import ru.taska.transport.grpc.GrpcNotificationServiceClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@WebFluxTest(controllers = NotificationController.class)
@Import({
        GatewayRequestExecutor.class,
        GatewayContextFactory.class,
        RequestIdProvider.class,
        BearerTokenExtractor.class,
        GatewayErrorHandler.class,
        RestErrorMapper.class
})
@DisplayName("NotificationController WebTestClient Tests")
class NotificationControllerWebTestClientTest {

    private static final String TOKEN = "valid-access-token";
    private static final String REQUEST_ID = "req-notifications";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GrpcNotificationServiceClient grpcNotificationServiceClient;

    @MockitoBean
    private NotificationMapper notificationMapper;

    @MockitoBean
    private GrpcAuthServiceClient grpcAuthServiceClient;

    @MockitoBean
    private ContextMapper contextMapper;

    @MockitoSpyBean
    private GatewayContextFactory contextFactory;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(contextFactory, "nodeId", "gateway-test-node");
    }

    @Test
    @DisplayName("GET /api/v1/notifications - успешный список уведомлений")
    void listNotifications_success_returns200AndItems() {
        mockAuthenticatedUser();

        ListNotificationsResponse grpcResponse = ListNotificationsResponse.newBuilder()
                .addNotifications(notification())
                .build();

        NotificationListResponseDto restResponse = new NotificationListResponseDto();
        restResponse.setItems(List.of(restNotification()));

        Mockito.when(grpcNotificationServiceClient.listNotifications(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(false),
                        ArgumentMatchers.eq(20),
                        ArgumentMatchers.eq(0L)
                ))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(notificationMapper.toRestListResponse(grpcResponse))
                .thenReturn(restResponse);

        webTestClient.get()
                .uri("/api/v1/notifications?unreadOnly=false&pageSize=20&offset=0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", REQUEST_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", REQUEST_ID)
                .expectBody()
                .jsonPath("$.items[0].id").isEqualTo("906b9963-9511-4508-b546-d398f62f5765")
                .jsonPath("$.items[0].notificationType").isEqualTo("ISSUE_ASSIGNED")
                .jsonPath("$.items[0].title").isEqualTo("Вас назначили исполнителем")
                .jsonPath("$.items[0].readAt").doesNotExist();

        Mockito.verify(grpcNotificationServiceClient).listNotifications(
                ArgumentMatchers.argThat(context -> USER_ID.equals(context.userContext().userId())),
                ArgumentMatchers.eq(false),
                ArgumentMatchers.eq(20),
                ArgumentMatchers.eq(0L)
        );
    }

    @Test
    @DisplayName("GET /api/v1/notifications - успешный список только непрочитанных")
    void listNotifications_unreadOnly_returns200() {
        mockAuthenticatedUser();

        ListNotificationsResponse grpcResponse = ListNotificationsResponse.newBuilder().build();

        NotificationListResponseDto restResponse = new NotificationListResponseDto();
        restResponse.setItems(List.of());

        Mockito.when(grpcNotificationServiceClient.listNotifications(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(true),
                        ArgumentMatchers.eq(20),
                        ArgumentMatchers.eq(0L)
                ))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(notificationMapper.toRestListResponse(grpcResponse))
                .thenReturn(restResponse);

        webTestClient.get()
                .uri("/api/v1/notifications?unreadOnly=true&pageSize=20&offset=0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-unread")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-unread")
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.items.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("GET /api/v1/notifications - без Bearer token возвращает 401")
    void listNotifications_missingToken_returns401() {
        webTestClient.get()
                .uri("/api/v1/notifications")
                .header("X-Request-Id", "req-no-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Request-Id", "req-no-token")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("Authorization header is missing");
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{notificationId}/read - успешная отметка прочитанным")
    void markNotificationAsRead_success_returns200AndNotification() {
        mockAuthenticatedUser();

        UUID notificationId = UUID.fromString("906b9963-9511-4508-b546-d398f62f5765");

        MarkAsReadResponse grpcResponse = MarkAsReadResponse.newBuilder()
                .setNotification(notification())
                .build();

        NotificationResponseDto restResponse = restNotification();
        restResponse.setReadAt(OffsetDateTime.parse("2026-07-06T12:00:00Z"));

        Mockito.when(grpcNotificationServiceClient.markAsRead(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(notificationId.toString())
                ))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(notificationMapper.toRestResponse(grpcResponse.getNotification()))
                .thenReturn(restResponse);

        webTestClient.patch()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-mark-read")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-Id", "req-mark-read")
                .expectBody()
                .jsonPath("$.id").isEqualTo(notificationId.toString())
                .jsonPath("$.notificationType").isEqualTo("ISSUE_ASSIGNED")
                .jsonPath("$.readAt").isEqualTo("2026-07-06T12:00:00Z");

        Mockito.verify(grpcNotificationServiceClient).markAsRead(
                ArgumentMatchers.argThat(context -> USER_ID.equals(context.userContext().userId())),
                ArgumentMatchers.eq(notificationId.toString())
        );
    }

    @Test
    @DisplayName("GET /api/v1/notifications - UNAVAILABLE от downstream возвращает 503")
    void listNotifications_downstreamUnavailable_returns503() {
        mockAuthenticatedUser();

        StatusRuntimeException grpcError = Status.UNAVAILABLE
                .withDescription("notification-service unavailable")
                .asRuntimeException();

        Mockito.when(grpcNotificationServiceClient.listNotifications(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()
                ))
                .thenReturn(Mono.error(grpcError));

        webTestClient.get()
                .uri("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-unavailable")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().valueEquals("X-Request-Id", "req-unavailable")
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("notification-service unavailable");
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{notificationId}/read - DEADLINE_EXCEEDED возвращает 504")
    void markNotificationAsRead_deadlineExceeded_returns504() {
        mockAuthenticatedUser();

        UUID notificationId = UUID.randomUUID();

        StatusRuntimeException grpcError = Status.DEADLINE_EXCEEDED
                .withDescription("deadline exceeded")
                .asRuntimeException();

        Mockito.when(grpcNotificationServiceClient.markAsRead(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(notificationId.toString())
                ))
                .thenReturn(Mono.error(grpcError));

        webTestClient.patch()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-timeout")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().valueEquals("X-Request-Id", "req-timeout")
                .expectBody()
                .jsonPath("$.code").isEqualTo("DEADLINE_EXCEEDED")
                .jsonPath("$.message").isEqualTo("deadline exceeded");
    }

    @Test
    @DisplayName("GET /api/v1/notifications - pageSize меньше 1 возвращает 400")
    void listNotifications_invalidPageSize_returns400() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/notifications?pageSize=0&offset=0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-invalid-page-size")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Request-Id", "req-invalid-page-size");
    }

    @Test
    @DisplayName("GET /api/v1/notifications - offset меньше 0 возвращает 400")
    void listNotifications_invalidOffset_returns400() {
        mockAuthenticatedUser();

        webTestClient.get()
                .uri("/api/v1/notifications?pageSize=20&offset=-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-invalid-offset")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Request-Id", "req-invalid-offset");
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{notificationId}/read - NOT_FOUND от downstream возвращает 404")
    void markNotificationAsRead_notFound_returns404() {
        mockAuthenticatedUser();

        UUID notificationId = UUID.randomUUID();

        StatusRuntimeException grpcError = Status.NOT_FOUND
                .withDescription("Notification not found")
                .asRuntimeException();

        Mockito.when(grpcNotificationServiceClient.markAsRead(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(notificationId.toString())
                ))
                .thenReturn(Mono.error(grpcError));

        webTestClient.patch()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-not-found")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Request-Id", "req-not-found")
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.message").isEqualTo("Notification not found");
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{notificationId}/read - чужое уведомление возвращает 403")
    void markNotificationAsRead_foreignNotification_returns403() {
        mockAuthenticatedUser();

        UUID notificationId = UUID.randomUUID();

        StatusRuntimeException grpcError = Status.PERMISSION_DENIED
                .withDescription("Notification belongs to another user")
                .asRuntimeException();

        Mockito.when(grpcNotificationServiceClient.markAsRead(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(notificationId.toString())
                ))
                .thenReturn(Mono.error(grpcError));

        webTestClient.patch()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-foreign-notification")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("X-Request-Id", "req-foreign-notification")
                .expectBody()
                .jsonPath("$.code").isEqualTo("PERMISSION_DENIED")
                .jsonPath("$.message").isEqualTo("Notification belongs to another user");
    }

    private void mockAuthenticatedUser() {
        ValidateAccessTokenResponse grpcResponse = ValidateAccessTokenResponse.newBuilder()
                .setUserContext(UserContext.newBuilder()
                        .setUserId(USER_ID)
                        .build())
                .build();

        GatewayUserContext gatewayUserContext = new GatewayUserContext(
                USER_ID,
                "testuser",
                "test@example.com",
                "Test User",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER
        );

        Mockito.when(grpcAuthServiceClient.validateAccessToken(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(TOKEN)
                ))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(contextMapper.mapToGatewayUserContext(grpcResponse.getUserContext()))
                .thenReturn(gatewayUserContext);
    }

    private NotificationResponse notification() {
        return NotificationResponse.newBuilder()
                .setId("906b9963-9511-4508-b546-d398f62f5765")
                .setNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED)
                .setTitle("Вас назначили исполнителем")
                .setBody("Вы назначены исполнителем задачи TASKA-12")
                .setLink("/projects/TASKA/issues/TASKA-12")
                .setSourceEventId("15cc2395-1a23-4159-ae40-e058a7ab4131")
                .build();
    }

    private NotificationResponseDto restNotification() {
        NotificationResponseDto dto = new NotificationResponseDto();

        dto.setId(UUID.fromString("906b9963-9511-4508-b546-d398f62f5765"));
        dto.setNotificationType(NotificationTypeDto.ISSUE_ASSIGNED);
        dto.setTitle("Вас назначили исполнителем");
        dto.setBody("Вы назначены исполнителем задачи TASKA-12");
        dto.setLink("/projects/TASKA/issues/TASKA-12");
        dto.setCreatedAt(OffsetDateTime.parse("2026-07-06T11:30:00Z"));
        dto.setReadAt(null);
        dto.setSourceEventId(UUID.fromString("15cc2395-1a23-4159-ae40-e058a7ab4131"));

        return dto;
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{notificationId}/read - пустой ответ downstream возвращает 502")
    void markNotificationAsRead_missingNotification_returnsBadGateway() {
        mockAuthenticatedUser();

        UUID notificationId = UUID.randomUUID();

        MarkAsReadResponse grpcResponse = MarkAsReadResponse.newBuilder()
                .build();

        Mockito.when(grpcNotificationServiceClient.markAsRead(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.eq(notificationId.toString())
                ))
                .thenReturn(Mono.just(grpcResponse));

        webTestClient.patch()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header("X-Request-Id", "req-empty-notification")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().valueEquals(
                        "X-Request-Id",
                        "req-empty-notification"
                )
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_GATEWAY")
                .jsonPath("$.message")
                .isEqualTo("Invalid response from notification-service");

        Mockito.verify(notificationMapper, Mockito.never())
                .toRestResponse(ArgumentMatchers.any());
    }
}
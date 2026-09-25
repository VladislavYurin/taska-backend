package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAllAsReadRequest;
import ru.taska.api.notification.v1.MarkAllAsReadResponse;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GatewayUserStatus;
import ru.taska.domain.GlobalRole;
import ru.taska.mapper.NotificationMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcNotificationServiceClient Tests")
public class GrpcNotificationServiceClientTest {

    private static final String REQUEST_ID = "req-id";
    private static final String NODE_ID = "api-gateway";
    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";

    @Mock
    private ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service notificationServiceProperties;

    private GrpcNotificationServiceClient client;
    private GatewayContext context;

    @BeforeEach
    void setUp() {
        Mockito.when(properties.notificationService()).thenReturn(notificationServiceProperties);
        Mockito.when(notificationServiceProperties.deadlineDuration()).thenReturn(Duration.ofMillis(5000));
        Mockito.when(notificationServiceStub.withDeadlineAfter(
                        ArgumentMatchers.anyLong(),
                        ArgumentMatchers.any(TimeUnit.class)
                ))
                .thenReturn(notificationServiceStub);

        GatewayUserContext userContext = new GatewayUserContext(
                USER_ID,
                "testuser",
                "test@example.com",
                "Test User",
                GatewayUserStatus.ACTIVE,
                GlobalRole.USER
        );

        context = new GatewayContext(REQUEST_ID, NODE_ID, userContext);
        // NotificationMapper без зависимостей, поэтому используем настоящий
        client = new GrpcNotificationServiceClient(notificationServiceStub, properties, new NotificationMapper());
    }

    @Test
    @DisplayName("listNotifications должен собрать request с userId из GatewayContext и вернуть REST DTO")
    void listNotifications_validParams_buildsCorrectRequestAndReturnsResponse() {
        ListNotificationsResponse response = ListNotificationsResponse.newBuilder()
                .addNotifications(notification())
                .setUnreadCount(5)
                .build();

        Mockito.when(notificationServiceStub.listNotifications(ArgumentMatchers.any(ListNotificationsRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.listNotifications(context, true, 20, 0L))
                .assertNext(result -> {
                    Assertions.assertThat(result.getItems()).hasSize(1);
                    Assertions.assertThat(result.getUnreadCount()).isEqualTo(5);
                })
                .verifyComplete();

        ArgumentCaptor<ListNotificationsRequest> captor = ArgumentCaptor.forClass(ListNotificationsRequest.class);
        Mockito.verify(notificationServiceStub).listNotifications(captor.capture());

        ListNotificationsRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getUnreadOnly()).isTrue();
        Assertions.assertThat(request.getBody().getPageSize()).isEqualTo(20);
        Assertions.assertThat(request.getBody().getOffset()).isEqualTo(0L);

        Mockito.verify(notificationServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("listNotifications должен использовать default значения для optional query params")
    void listNotifications_nullParams_usesDefaultValues() {
        ListNotificationsResponse response = ListNotificationsResponse.newBuilder().build();

        Mockito.when(notificationServiceStub.listNotifications(ArgumentMatchers.any(ListNotificationsRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.listNotifications(context, null, null, null))
                .assertNext(result -> {
                    Assertions.assertThat(result.getItems()).isEmpty();
                    Assertions.assertThat(result.getUnreadCount()).isZero();
                })
                .verifyComplete();

        ArgumentCaptor<ListNotificationsRequest> captor = ArgumentCaptor.forClass(ListNotificationsRequest.class);
        Mockito.verify(notificationServiceStub).listNotifications(captor.capture());

        ListNotificationsRequest request = captor.getValue();

        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getUnreadOnly()).isFalse();
        Assertions.assertThat(request.getBody().getPageSize()).isEqualTo(20);
        Assertions.assertThat(request.getBody().getOffset()).isEqualTo(0L);
    }

    @Test
    @DisplayName("markAsRead должен собрать request с notificationId и userId из GatewayContext и вернуть REST DTO")
    void markAsRead_validParams_buildsCorrectRequestAndReturnsResponse() {
        String notificationId = UUID.randomUUID().toString();

        MarkAsReadResponse response = MarkAsReadResponse.newBuilder()
                .setNotification(notification())
                .build();

        Mockito.when(notificationServiceStub.markAsRead(ArgumentMatchers.any(MarkAsReadRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.markAsRead(context, notificationId))
                .assertNext(result -> Assertions.assertThat(result.getId()).isNotNull())
                .verifyComplete();

        ArgumentCaptor<MarkAsReadRequest> captor = ArgumentCaptor.forClass(MarkAsReadRequest.class);
        Mockito.verify(notificationServiceStub).markAsRead(captor.capture());

        MarkAsReadRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getNotificationId()).isEqualTo(notificationId);
        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);

        Mockito.verify(notificationServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("markAllAsRead должен собрать request с userId из GatewayContext и вернуть REST DTO")
    void markAllAsRead_validParams_buildsCorrectRequestAndReturnsMappedResponse() {
        MarkAllAsReadResponse response = MarkAllAsReadResponse.newBuilder()
                .setUpdatedCount(40L)
                .build();

        Mockito.when(notificationServiceStub.markAllAsRead(ArgumentMatchers.any(MarkAllAsReadRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.markAllAsRead(context))
                .assertNext(result -> Assertions.assertThat(result.getUpdatedCount()).isEqualTo(40L))
                .verifyComplete();

        ArgumentCaptor<MarkAllAsReadRequest> captor = ArgumentCaptor.forClass(MarkAllAsReadRequest.class);
        Mockito.verify(notificationServiceStub).markAllAsRead(captor.capture());

        MarkAllAsReadRequest request = captor.getValue();

        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);

        Mockito.verify(notificationServiceStub).withDeadlineAfter(5000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("markAllAsRead должен вернуть updatedCount = 0, если непрочитанных нет")
    void markAllAsRead_nothingToMark_returnsZero() {
        MarkAllAsReadResponse response = MarkAllAsReadResponse.newBuilder()
                .setUpdatedCount(0L)
                .build();

        Mockito.when(notificationServiceStub.markAllAsRead(ArgumentMatchers.any(MarkAllAsReadRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.markAllAsRead(context))
                .assertNext(result -> Assertions.assertThat(result.getUpdatedCount()).isEqualTo(0L))
                .verifyComplete();
    }

    @Test
    @DisplayName("Должен пробросить ошибку, если notification-service вернул UNAVAILABLE")
    void listNotifications_downstreamUnavailable_propagatesError() {
        StatusRuntimeException grpcError = Status.UNAVAILABLE
                .withDescription("notification-service unavailable")
                .asRuntimeException();

        Mockito.when(notificationServiceStub.listNotifications(ArgumentMatchers.any(ListNotificationsRequest.class)))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.listNotifications(context, false, 20, 0L))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException
                                && ((StatusRuntimeException) error).getStatus().getCode() == Status.Code.UNAVAILABLE
                )
                .verify();
    }

    @Test
    @DisplayName("Должен пробросить ошибку, если notification-service вернул DEADLINE_EXCEEDED")
    void markAsRead_deadlineExceeded_propagatesError() {
        StatusRuntimeException grpcError = Status.DEADLINE_EXCEEDED
                .withDescription("deadline exceeded")
                .asRuntimeException();

        Mockito.when(notificationServiceStub.markAsRead(ArgumentMatchers.any(MarkAsReadRequest.class)))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.markAsRead(context, UUID.randomUUID().toString()))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException
                                && ((StatusRuntimeException) error).getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED
                )
                .verify();
    }

    @Test
    @DisplayName("markAllAsRead должен пробросить ошибку, если notification-service вернул UNAVAILABLE")
    void markAllAsRead_downstreamUnavailable_propagatesError() {
        StatusRuntimeException grpcError = Status.UNAVAILABLE
                .withDescription("notification-service unavailable")
                .asRuntimeException();

        Mockito.when(notificationServiceStub.markAllAsRead(ArgumentMatchers.any(MarkAllAsReadRequest.class)))
                .thenReturn(Mono.error(grpcError));

        StepVerifier.create(client.markAllAsRead(context))
                .expectErrorMatches(error ->
                        error instanceof StatusRuntimeException
                                && ((StatusRuntimeException) error).getStatus().getCode() == Status.Code.UNAVAILABLE
                )
                .verify();
    }

    private NotificationResponse notification() {
        return NotificationResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED)
                .setTitle("Вас назначили исполнителем")
                .setBody("Вы назначены исполнителем задачи TASKA-12")
                .setSourceEventId(UUID.randomUUID().toString())
                .build();
    }
}
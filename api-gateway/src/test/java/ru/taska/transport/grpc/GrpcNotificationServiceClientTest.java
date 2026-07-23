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
        client = new GrpcNotificationServiceClient(notificationServiceStub, properties);
    }

    @Test
    @DisplayName("listNotifications должен собрать request с userId из GatewayContext")
    void listNotifications_validParams_buildsCorrectRequestAndReturnsResponse() {
        ListNotificationsResponse response = ListNotificationsResponse.newBuilder()
                .addNotifications(notification())
                .build();

        Mockito.when(notificationServiceStub.listNotifications(ArgumentMatchers.any(ListNotificationsRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.listNotifications(context, true, 20, 0L))
                .assertNext(result -> Assertions.assertThat(result.getNotificationsList()).hasSize(1))
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
                .assertNext(result -> Assertions.assertThat(result.getNotificationsList()).isEmpty())
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
    @DisplayName("markAsRead должен собрать request с notificationId и userId из GatewayContext")
    void markAsRead_validParams_buildsCorrectRequestAndReturnsResponse() {
        String notificationId = UUID.randomUUID().toString();

        MarkAsReadResponse response = MarkAsReadResponse.newBuilder()
                .setNotification(notification())
                .build();

        Mockito.when(notificationServiceStub.markAsRead(ArgumentMatchers.any(MarkAsReadRequest.class)))
                .thenReturn(Mono.just(response));

        StepVerifier.create(client.markAsRead(context, notificationId))
                .assertNext(result -> Assertions.assertThat(result.getNotification().getId()).isNotBlank())
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
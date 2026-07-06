package ru.taska.transport.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.notification.v1.ListNotificationsRequestBody;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.MarkAsReadRequestBody;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.mapper.NotificationMapper;
import ru.taska.service.NotificationInboxService;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class GrpcNotificationServiceTest {

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000200");

    private static final Instant CREATED_AT = Instant.parse("2026-05-26T08:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-05-26T08:30:00Z");

    private static final int PAGE_SIZE = 20;
    private static final long OFFSET = 0;

    @Mock
    private NotificationInboxService notificationInboxService;

    private GrpcNotificationService grpcNotificationService;

    @BeforeEach
    void setUp() {
        grpcNotificationService = new GrpcNotificationService(
                notificationInboxService,
                new NotificationMapper()
        );
    }

    @Test
    void shouldListNotifications() {
        Mockito.when(notificationInboxService.listNotifications(USER_ID, true, PAGE_SIZE, OFFSET))
                .thenReturn(Flux.just(notification(null)));

        StepVerifier.create(grpcNotificationService.listNotifications(Mono.just(listRequest(true))))
                .assertNext(response -> {
                    Assertions.assertEquals(1, response.getNotificationsCount());

                    NotificationResponse item = response.getNotifications(0);

                    Assertions.assertEquals(NOTIFICATION_ID.toString(), item.getId());
                    Assertions.assertEquals(USER_ID.toString(), item.getUserId());
                    Assertions.assertEquals(
                            NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED,
                            item.getNotificationType()
                    );
                    Assertions.assertTrue(item.hasCreatedAt());
                    Assertions.assertFalse(item.hasReadAt());
                })
                .verifyComplete();

        Mockito.verify(notificationInboxService)
                .listNotifications(USER_ID, true, PAGE_SIZE, OFFSET);
    }

    @Test
    void shouldMarkNotificationAsRead() {
        Mockito.when(notificationInboxService.markAsRead(NOTIFICATION_ID, USER_ID))
                .thenReturn(Mono.just(notification(READ_AT)));

        StepVerifier.create(grpcNotificationService.markAsRead(Mono.just(markAsReadRequest(
                        NOTIFICATION_ID.toString(),
                        USER_ID.toString()
                ))))
                .assertNext(response -> {
                    NotificationResponse item = response.getNotification();

                    Assertions.assertEquals(NOTIFICATION_ID.toString(), item.getId());
                    Assertions.assertTrue(item.hasReadAt());
                    Assertions.assertEquals(READ_AT.getEpochSecond(), item.getReadAt().getSeconds());
                })
                .verifyComplete();

        Mockito.verify(notificationInboxService)
                .markAsRead(NOTIFICATION_ID, USER_ID);
    }

    @Test
    void shouldReturnInvalidArgumentForInvalidNotificationId() {
        StepVerifier.create(grpcNotificationService.markAsRead(Mono.just(markAsReadRequest(
                        "not-a-uuid",
                        USER_ID.toString()
                ))))
                .expectErrorMatches(error -> error instanceof StatusRuntimeException statusException
                        && statusException.getStatus().getCode() == Status.Code.INVALID_ARGUMENT)
                .verify();

        Mockito.verifyNoInteractions(notificationInboxService);
    }

    private static ListNotificationsRequest listRequest(boolean unreadOnly) {
        return ListNotificationsRequest.newBuilder()
                .setHeader(header())
                .setBody(ListNotificationsRequestBody.newBuilder()
                        .setUserId(USER_ID.toString())
                        .setUnreadOnly(unreadOnly)
                        .setPageSize(PAGE_SIZE)
                        .setOffset(OFFSET)
                        .build())
                .build();
    }

    private static MarkAsReadRequest markAsReadRequest(String notificationId, String userId) {
        return MarkAsReadRequest.newBuilder()
                .setHeader(header())
                .setBody(MarkAsReadRequestBody.newBuilder()
                        .setNotificationId(notificationId)
                        .setUserId(userId)
                        .build())
                .build();
    }

    private static Header header() {
        return Header.newBuilder()
                .setRequestId("request-id")
                .setNodeId("test-node")
                .build();
    }

    private static Notification notification(Instant readAt) {
        return Notification.builder()
                .id(NOTIFICATION_ID)
                .userId(USER_ID)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Issue assigned")
                .body("You have a new issue")
                .link("/issues/TAS-35")
                .createdAt(CREATED_AT)
                .readAt(readAt)
                .sourceEventId(SOURCE_EVENT_ID)
                .build();
    }
}
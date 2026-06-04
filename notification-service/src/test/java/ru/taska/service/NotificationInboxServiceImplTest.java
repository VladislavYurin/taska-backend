package ru.taska.service;

import ru.taska.exception.DomainException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.repository.NotificationRepository;

import java.time.Instant;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class NotificationInboxServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SOURCE_EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final int PAGE_SIZE = 20;
    private static final long OFFSET = 0;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationInboxServiceImpl notificationInboxService;

    @Test
    void shouldListUnreadNotificationsWhenUnreadOnlyTrue() {
        Notification notification = notification(null);

        Mockito.when(notificationRepository.findUnreadByUserId(USER_ID, PAGE_SIZE, OFFSET))
                .thenReturn(Flux.just(notification));

        StepVerifier.create(notificationInboxService.listNotifications(USER_ID, true, PAGE_SIZE, OFFSET))
                .expectNext(notification)
                .verifyComplete();

        Mockito.verify(notificationRepository, Mockito.times(1))
                .findUnreadByUserId(USER_ID, PAGE_SIZE, OFFSET);

        Mockito.verify(notificationRepository, Mockito.never())
                .findAllByUserId(
                        ArgumentMatchers.any(UUID.class),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.anyLong()
                );
    }

    @Test
    void shouldListAllNotificationsWhenUnreadOnlyFalse() {
        Notification notification = notification(Instant.parse("2026-05-27T10:00:00Z"));

        Mockito.when(notificationRepository.findAllByUserId(USER_ID, PAGE_SIZE, OFFSET))
                .thenReturn(Flux.just(notification));

        StepVerifier.create(notificationInboxService.listNotifications(USER_ID, false, PAGE_SIZE, OFFSET))
                .expectNext(notification)
                .verifyComplete();

        Mockito.verify(notificationRepository, Mockito.times(1))
                .findAllByUserId(USER_ID, PAGE_SIZE, OFFSET);

        Mockito.verify(notificationRepository, Mockito.never())
                .findUnreadByUserId(
                        ArgumentMatchers.any(UUID.class),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.anyLong()
                );
    }

    @Test
    void shouldMarkUnreadNotificationAsRead() {
        Notification unreadNotification = notification(null);

        Mockito.when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Mono.just(unreadNotification));

        Mockito.when(notificationRepository.save(ArgumentMatchers.any(Notification.class)))
                .thenAnswer(invocation -> Mono.just((Notification) invocation.getArgument(0)));

        StepVerifier.create(notificationInboxService.markAsRead(NOTIFICATION_ID, USER_ID))
                .assertNext(updatedNotification -> {
                    Assertions.assertThat(updatedNotification.getId()).isEqualTo(NOTIFICATION_ID);
                    Assertions.assertThat(updatedNotification.getUserId()).isEqualTo(USER_ID);
                    Assertions.assertThat(updatedNotification.getReadAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);

        Mockito.verify(notificationRepository, Mockito.times(1))
                .save(captor.capture());

        Assertions.assertThat(captor.getValue().getReadAt()).isNotNull();
    }

    @Test
    void shouldReturnAlreadyReadNotificationWithoutSaving() {
        Instant readAt = Instant.parse("2026-05-27T10:00:00Z");
        Notification readNotification = notification(readAt);

        Mockito.when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Mono.just(readNotification));

        StepVerifier.create(notificationInboxService.markAsRead(NOTIFICATION_ID, USER_ID))
                .expectNext(readNotification)
                .verifyComplete();

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any(Notification.class));
    }

    @Test
    void shouldFailWhenNotificationNotFound() {
        Mockito.when(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(notificationInboxService.markAsRead(NOTIFICATION_ID, USER_ID))
                .expectError(DomainException.class)
                .verify();

        Mockito.verify(notificationRepository, Mockito.never())
                .save(ArgumentMatchers.any(Notification.class));
    }

    private Notification notification(Instant readAt) {
        return Notification.builder()
                .id(NOTIFICATION_ID)
                .userId(USER_ID)
                .notificationType(NotificationType.ISSUE_ASSIGNED)
                .title("Test notification")
                .body("Test body")
                .link("/test")
                .createdAt(Instant.parse("2026-05-27T09:00:00Z"))
                .readAt(readAt)
                .sourceEventId(SOURCE_EVENT_ID)
                .build();
    }
}
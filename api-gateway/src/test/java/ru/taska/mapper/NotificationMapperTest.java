package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationMapper Tests")
class NotificationMapperTest {

    private final NotificationMapper mapper = new NotificationMapper();

    @Test
    @DisplayName("Должен корректно маппить уведомление с readAt в REST DTO")
    void toRestResponse_notificationWithReadAt_mapsAllFields() {
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();

        NotificationResponse source = NotificationResponse.newBuilder()
                .setId(notificationId.toString())
                .setNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED)
                .setTitle("Вас назначили исполнителем")
                .setBody("Вы назначены исполнителем задачи TASKA-12")
                .setLink("/projects/TASKA/issues/TASKA-12")
                .setCreatedAt(timestamp("2026-07-06T11:30:00Z"))
                .setReadAt(timestamp("2026-07-06T12:00:00Z"))
                .setSourceEventId(sourceEventId.toString())
                .build();

        NotificationResponseDto result = mapper.toRestResponse(source);

        assertThat(result.getId()).isEqualTo(notificationId);
        assertThat(result.getNotificationType()).isEqualTo("ISSUE_ASSIGNED");
        assertThat(result.getTitle()).isEqualTo("Вас назначили исполнителем");
        assertThat(result.getBody()).isEqualTo("Вы назначены исполнителем задачи TASKA-12");
        assertThat(result.getLink()).isEqualTo("/projects/TASKA/issues/TASKA-12");
        assertThat(result.getCreatedAt()).isEqualTo(offsetDateTime("2026-07-06T11:30:00Z"));
        assertThat(result.getReadAt()).isEqualTo(offsetDateTime("2026-07-06T12:00:00Z"));
        assertThat(result.getSourceEventId()).isEqualTo(sourceEventId);
    }

    @Test
    @DisplayName("Должен возвращать readAt=null для непрочитанного уведомления")
    void toRestResponse_unreadNotification_mapsReadAtAsNull() {
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();

        NotificationResponse source = NotificationResponse.newBuilder()
                .setId(notificationId.toString())
                .setNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_CREATED)
                .setTitle("Создана новая задача")
                .setBody("Создана задача TASKA-12")
                .setCreatedAt(timestamp("2026-07-06T11:30:00Z"))
                .setSourceEventId(sourceEventId.toString())
                .build();

        NotificationResponseDto result = mapper.toRestResponse(source);

        assertThat(result.getId()).isEqualTo(notificationId);
        assertThat(result.getNotificationType()).isEqualTo("ISSUE_CREATED");
        assertThat(result.getReadAt()).isNull();
        assertThat(result.getSourceEventId()).isEqualTo(sourceEventId);
    }

    @Test
    @DisplayName("Должен маппить список уведомлений в items")
    void toRestListResponse_mapsNotificationsToItems() {
        NotificationResponse first = notification(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED);
        NotificationResponse second = notification(NotificationKind.NOTIFICATION_KIND_PROJECT_CREATED);

        ListNotificationsResponse source = ListNotificationsResponse.newBuilder()
                .addNotifications(first)
                .addNotifications(second)
                .build();

        NotificationListResponseDto result = mapper.toRestListResponse(source);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getNotificationType()).isEqualTo("ISSUE_ASSIGNED");
        assertThat(result.getItems().get(1).getNotificationType()).isEqualTo("PROJECT_CREATED");
    }

    @Test
    @DisplayName("Должен маппить все notificationType без protobuf prefix")
    void toRestNotificationType_mapsAllSupportedTypes() {
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED))
                .isEqualTo("ISSUE_ASSIGNED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_TRANSITIONED))
                .isEqualTo("ISSUE_TRANSITIONED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_CREATED))
                .isEqualTo("ISSUE_CREATED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_UPDATED))
                .isEqualTo("ISSUE_UPDATED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_DELETED))
                .isEqualTo("ISSUE_DELETED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_USER_INVITED))
                .isEqualTo("USER_INVITED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_USER_ACTIVATED))
                .isEqualTo("USER_ACTIVATED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_PROJECT_CREATED))
                .isEqualTo("PROJECT_CREATED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_MEMBER_ADDED))
                .isEqualTo("MEMBER_ADDED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_MEMBER_UPDATED))
                .isEqualTo("MEMBER_UPDATED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_MEMBER_REMOVED))
                .isEqualTo("MEMBER_REMOVED");
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_UNSPECIFIED))
                .isEqualTo("UNSPECIFIED");
    }

    private NotificationResponse notification(NotificationKind kind) {
        return NotificationResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setNotificationType(kind)
                .setTitle("title")
                .setBody("body")
                .setCreatedAt(timestamp("2026-07-06T11:30:00Z"))
                .setSourceEventId(UUID.randomUUID().toString())
                .build();
    }

    private Timestamp timestamp(String value) {
        Instant instant = Instant.parse(value);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private OffsetDateTime offsetDateTime(String value) {
        return OffsetDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Должен вернуть BAD_GATEWAY при некорректном id от notification-service")
    void toRestResponse_invalidId_throwsBadGateway() {
        NotificationResponse source = NotificationResponse.newBuilder()
                .setId("invalid-uuid")
                .setSourceEventId(UUID.randomUUID().toString())
                .build();

        Assertions.assertThatThrownBy(() -> mapper.toRestResponse(source))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    Assertions.assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY);

                    Assertions.assertThat(exception.getReason())
                            .isEqualTo(
                                    "Invalid id received from notification-service"
                            );
                });
    }

    @Test
    @DisplayName("Должен вернуть BAD_GATEWAY при некорректном sourceEventId от notification-service")
    void toRestResponse_invalidSourceEventId_throwsBadGateway() {
        NotificationResponse source = NotificationResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setNotificationType(NotificationKind.NOTIFICATION_KIND_ISSUE_ASSIGNED)
                .setTitle("title")
                .setBody("body")
                .setCreatedAt(timestamp("2026-07-06T11:30:00Z"))
                .setSourceEventId("invalid-uuid")
                .build();

        Assertions.assertThatThrownBy(() -> mapper.toRestResponse(source))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException exception =
                            (ResponseStatusException) error;

                    Assertions.assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY);

                    Assertions.assertThat(exception.getReason())
                            .isEqualTo(
                                    "Invalid sourceEventId received from notification-service"
                            );
                });
    }

    @Test
    @DisplayName("Должен вернуть строку для неизвестного notificationType")
    void toRestNotificationType_unknownType_returnsString() {
        assertThat(mapper.toRestNotificationType(NotificationKind.NOTIFICATION_KIND_UNSPECIFIED))
                .isEqualTo("UNSPECIFIED");
    }
}
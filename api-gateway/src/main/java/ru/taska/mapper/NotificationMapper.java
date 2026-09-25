package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAllAsReadResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.domain.dto.ReadAllNotificationsResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Маппер моделей уведомлений между gRPC и REST слоями API Gateway.
 * <p>
 * Наружу не отдаётся технический gRPC-префикс {@code NOTIFICATION_KIND_}, только
 * frontend-friendly значения {@code notificationType} из OpenAPI контракта.
 */
@Slf4j
@Component
public class NotificationMapper {

    private static final String NOTIFICATION_KIND_PREFIX = "NOTIFICATION_KIND_";
    private static final String UNKNOWN_NOTIFICATION_TYPE = "UNKNOWN";

    /**
     * Список уведомлений всегда возвращается в поле {@code items}, даже если он пуст.
     */
    public NotificationListResponseDto toNotificationListResponseDto(ListNotificationsResponse source) {
        NotificationListResponseDto dto = new NotificationListResponseDto();

        dto.setItems(
                source.getNotificationsList().stream()
                        .map(this::toNotificationResponseDto)
                        .toList()
        );
        dto.setUnreadCount(source.getUnreadCount());

        return dto;
    }

    public ReadAllNotificationsResponseDto toReadAllNotificationsResponseDto(MarkAllAsReadResponse source) {
        return new ReadAllNotificationsResponseDto(source.getUpdatedCount());
    }

    /**
     * Строковые id из Protobuf контракта приводятся к {@link UUID}, {@code readAt}
     * для непрочитанного уведомления остаётся {@code null}.
     */
    public NotificationResponseDto toNotificationResponseDto(NotificationResponse source) {
        NotificationResponseDto dto = new NotificationResponseDto();

        dto.setId(parseUuid(source.getId(), "id"));
        dto.setNotificationType(toRestNotificationType(source.getNotificationType()));
        dto.setTitle(source.getTitle());
        dto.setBody(source.getBody());
        dto.setLink(source.getLink());
        dto.setCreatedAt(toOffsetDateTime(source.getCreatedAt()));
        dto.setReadAt(source.hasReadAt() ? toOffsetDateTime(source.getReadAt()) : null);
        dto.setSourceEventId(parseUuid(source.getSourceEventId(), "sourceEventId"));

        return dto;
    }

    /**
     * Отсекает технический префикс {@code NOTIFICATION_KIND_} у enum-константы.
     * <p>
     * Неизвестные и нераспознанные (например, {@code UNRECOGNIZED} — новый kind,
     * ещё не задеплоенный на стороне gateway) значения не роняют запрос, а
     * логируются и отдаются как {@value #UNKNOWN_NOTIFICATION_TYPE}, чтобы
     * появление нового типа уведомления на notification-service не приводило
     * к 502 на весь список для пользователя.
     */
    public String toRestNotificationType(NotificationKind source) {
        return switch (source) {
            case NOTIFICATION_KIND_ISSUE_ASSIGNED -> "ISSUE_ASSIGNED";
            case NOTIFICATION_KIND_ISSUE_TRANSITIONED -> "ISSUE_TRANSITIONED";
            case NOTIFICATION_KIND_ISSUE_CREATED -> "ISSUE_CREATED";
            case NOTIFICATION_KIND_ISSUE_UPDATED -> "ISSUE_UPDATED";
            case NOTIFICATION_KIND_ISSUE_DELETED -> "ISSUE_DELETED";
            case NOTIFICATION_KIND_USER_INVITED -> "USER_INVITED";
            case NOTIFICATION_KIND_USER_ACTIVATED -> "USER_ACTIVATED";
            case NOTIFICATION_KIND_PROJECT_CREATED -> "PROJECT_CREATED";
            case NOTIFICATION_KIND_MEMBER_ADDED -> "MEMBER_ADDED";
            case NOTIFICATION_KIND_MEMBER_UPDATED -> "MEMBER_UPDATED";
            case NOTIFICATION_KIND_MEMBER_REMOVED -> "MEMBER_REMOVED";
            case NOTIFICATION_KIND_LABEL_ADDED -> "LABEL_ADDED";
            case NOTIFICATION_KIND_LABEL_REMOVED -> "LABEL_REMOVED";
            case UNRECOGNIZED -> {
                log.warn("Received UNRECOGNIZED NotificationKind from notification-service, " +
                        "gateway proto is likely outdated");
                yield UNKNOWN_NOTIFICATION_TYPE;
            }
            default -> {
                String name = source.name();
                log.warn("Received unmapped NotificationKind={} from notification-service", name);
                yield name.startsWith(NOTIFICATION_KIND_PREFIX)
                        ? name.substring(NOTIFICATION_KIND_PREFIX.length())
                        : name;
            }
        };
    }

    private OffsetDateTime toOffsetDateTime(Timestamp source) {
        Instant instant = Instant.ofEpochSecond(source.getSeconds(), source.getNanos());
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw invalidDownstreamField(fieldName, null);
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalidDownstreamField(fieldName, exception);
        }
    }

    private ResponseStatusException invalidDownstreamField(
            String fieldName,
            Throwable cause
    ) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Invalid " + fieldName + " received from notification-service",
                cause
        );
    }
}
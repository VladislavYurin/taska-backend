package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.NotificationKind;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.domain.dto.NotificationTypeDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Компонент-маппер для преобразования моделей уведомлений между gRPC и REST слоями API Gateway.
 * <p>
 * Преобразует Protobuf сообщения notification-service в REST DTO, которые возвращаются frontend-клиенту.
 * Также очищает gRPC-специфичные enum-префиксы, например
 * {@code NOTIFICATION_KIND_ISSUE_ASSIGNED} преобразуется в {@code ISSUE_ASSIGNED}.
 */
@Component
public class NotificationMapper {

    /**
     * Преобразует gRPC ответ со списком уведомлений в REST DTO ответа.
     * <p>
     * В REST API список уведомлений всегда возвращается внутри поля {@code items}.
     * Даже если уведомлений нет, клиент получает пустой список, а не ошибку 404.
     *
     * @param source ответ {@link ListNotificationsResponse}, полученный от notification-service
     * @return заполненный REST DTO {@link NotificationListResponseDto} со списком уведомлений
     */
    public NotificationListResponseDto toRestListResponse(ListNotificationsResponse source) {
        NotificationListResponseDto dto = new NotificationListResponseDto();

        dto.setItems(
                source.getNotificationsList().stream()
                        .map(this::toRestResponse)
                        .toList()
        );

        return dto;
    }

    /**
     * Преобразует одно gRPC уведомление в REST DTO.
     * <p>
     * Строковые идентификаторы из Protobuf контракта приводятся к {@link UUID}.
     * Временные метки Protobuf {@link Timestamp} преобразуются в {@link OffsetDateTime} в UTC.
     * Для непрочитанных уведомлений поле {@code readAt} остаётся {@code null}.
     *
     * @param source gRPC объект {@link NotificationResponse}, полученный от notification-service
     * @return заполненный REST DTO {@link NotificationResponseDto} для ответа frontend-клиенту
     */
    public NotificationResponseDto toRestResponse(NotificationResponse source) {
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
     * Преобразует тип уведомления из Protobuf enum в REST enum.
     * <p>
     * REST API не должен отдавать наружу технический gRPC-префикс {@code NOTIFICATION_KIND_},
     * поэтому значения приводятся к frontend-friendly формату из OpenAPI контракта.
     *
     * @param source тип уведомления {@link NotificationKind} из gRPC контракта
     * @return соответствующий REST enum {@link NotificationTypeDto}
     * @throws ResponseStatusException если notification-service вернул неподдерживаемый тип уведомления
     */
    public NotificationTypeDto toRestNotificationType(NotificationKind source) {
        return switch (source) {
            case NOTIFICATION_KIND_ISSUE_ASSIGNED -> NotificationTypeDto.ISSUE_ASSIGNED;
            case NOTIFICATION_KIND_ISSUE_TRANSITIONED -> NotificationTypeDto.ISSUE_TRANSITIONED;
            case NOTIFICATION_KIND_ISSUE_CREATED -> NotificationTypeDto.ISSUE_CREATED;
            case NOTIFICATION_KIND_ISSUE_UPDATED -> NotificationTypeDto.ISSUE_UPDATED;
            case NOTIFICATION_KIND_ISSUE_DELETED -> NotificationTypeDto.ISSUE_DELETED;
            case NOTIFICATION_KIND_USER_INVITED -> NotificationTypeDto.USER_INVITED;
            case NOTIFICATION_KIND_USER_ACTIVATED -> NotificationTypeDto.USER_ACTIVATED;
            case NOTIFICATION_KIND_PROJECT_CREATED -> NotificationTypeDto.PROJECT_CREATED;
            case NOTIFICATION_KIND_MEMBER_ADDED -> NotificationTypeDto.MEMBER_ADDED;
            case NOTIFICATION_KIND_MEMBER_UPDATED -> NotificationTypeDto.MEMBER_UPDATED;
            case NOTIFICATION_KIND_MEMBER_REMOVED -> NotificationTypeDto.MEMBER_REMOVED;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unsupported notification type received from notification-service"
            );
        };
    }

    /**
     * Преобразует Protobuf timestamp в {@link OffsetDateTime} с UTC offset.
     *
     * @param source временная метка из Protobuf сообщения
     * @return дата и время в формате UTC для REST ответа
     */
    private OffsetDateTime toOffsetDateTime(Timestamp source) {
        Instant instant = Instant.ofEpochSecond(source.getSeconds(), source.getNanos());
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw invalidDownstreamUuid(fieldName, null);
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalidDownstreamUuid(fieldName, exception);
        }
    }

    private ResponseStatusException invalidDownstreamUuid(
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
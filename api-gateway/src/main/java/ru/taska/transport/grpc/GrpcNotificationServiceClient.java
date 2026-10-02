package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsRequestBody;
import ru.taska.api.notification.v1.MarkAllAsReadRequest;
import ru.taska.api.notification.v1.MarkAllAsReadRequestBody;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadRequestBody;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.domain.dto.ReadAllNotificationsResponseDto;
import ru.taska.mapper.NotificationMapper;

import java.util.concurrent.TimeUnit;

/**
 * gRPC-клиент API Gateway для notification-service.
 * <p>
 * userId всегда берётся из {@link GatewayContext}, а не из запроса frontend-клиента.
 * К каждому вызову применяется deadline из {@link GrpcClientProperties}, ответы сразу маппятся в REST DTO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcNotificationServiceClient {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final long DEFAULT_OFFSET = 0L;

    private final ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub;
    private final GrpcClientProperties properties;
    private final NotificationMapper notificationMapper;


    /**
     * Возвращает inbox уведомления пользователя; не заданные параметры пагинации заменяются значениями по умолчанию.
     */
    public Mono<NotificationListResponseDto> listNotifications(
            GatewayContext context,
            Boolean unreadOnly,
            Integer pageSize,
            Long offset
    ) {
        log.info("[{}] Calling listNotifications", context.requestId());

        ListNotificationsRequest request = ListNotificationsRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(ListNotificationsRequestBody.newBuilder()
                        .setUserId(context.userContext().userId())
                        .setUnreadOnly(Boolean.TRUE.equals(unreadOnly))
                        .setPageSize(pageSize != null ? pageSize : DEFAULT_PAGE_SIZE)
                        .setOffset(offset != null ? offset : DEFAULT_OFFSET)
                        .build())
                .build();

        return stubWithDeadline().listNotifications(request)
                .map(notificationMapper::toNotificationListResponseDto);
    }

    /**
     * Отмечает уведомление прочитанным; принадлежность уведомления пользователю проверяет notification-service.
     */
    public Mono<NotificationResponseDto> markAsRead(
            GatewayContext context,
            String notificationId
    ) {
        log.info("[{}] Calling markAsRead", context.requestId());

        MarkAsReadRequest request = MarkAsReadRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(MarkAsReadRequestBody.newBuilder()
                        .setNotificationId(notificationId)
                        .setUserId(context.userContext().userId())
                        .build())
                .build();

        return stubWithDeadline().markAsRead(request)
                .map(response -> notificationMapper.toNotificationResponseDto(response.getNotification()));
    }

    /**
     * Отмечает прочитанными все непрочитанные уведомления пользователя одним вызовом.
     */
    public Mono<ReadAllNotificationsResponseDto> markAllAsRead(
            GatewayContext context
    ) {
        log.info("[{}] Calling markAllAsRead", context.requestId());

        MarkAllAsReadRequest request = MarkAllAsReadRequest.newBuilder()
                .setHeader(buildGrpcHeader(context))
                .setBody(MarkAllAsReadRequestBody.newBuilder()
                        .setUserId(context.userContext().userId())
                        .build())
                .build();

        return stubWithDeadline().markAllAsRead(request)
                .map(notificationMapper::toReadAllNotificationsResponseDto);
    }

    /**
     * Формирует общий gRPC-заголовок из gateway-контекста.
     */
    private Header buildGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }

    /**
     * Возвращает gRPC stub с динамически заданным deadline из конфигурации.
     */
    private ReactorNotificationServiceGrpc.ReactorNotificationServiceStub stubWithDeadline() {
        return notificationServiceStub.withDeadlineAfter(
                properties.notificationService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }
}

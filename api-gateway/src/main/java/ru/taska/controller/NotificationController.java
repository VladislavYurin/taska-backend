package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.NotificationsApi;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.domain.dto.ReadAllNotificationsResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcNotificationServiceClient;

import java.util.UUID;

import static ru.taska.domain.EndpointSecurity.PROTECTED;

/**
 * REST-контроллер API Gateway для работы с inbox уведомлениями текущего пользователя.
 * <p>
 * Реализует защищённые REST endpoints поверх gRPC API notification-service.
 * Gateway не принимает userId от frontend-клиента: идентификатор пользователя
 * берётся только из authenticated context, сформированного после проверки Bearer access token.
 * <p>
 * Реализует сгенерированный OpenAPI-интерфейс {@link NotificationsApi}.
 */
@RestController
@RequiredArgsConstructor
public class NotificationController implements NotificationsApi {

    private final GrpcNotificationServiceClient grpcNotificationServiceClient;
    private final GatewayRequestExecutor executor;

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<NotificationListResponseDto>> listNotifications(
            Boolean unreadOnly,
            Integer pageSize,
            Long offset,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context ->
                grpcNotificationServiceClient.listNotifications(context, unreadOnly, pageSize, offset)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<NotificationResponseDto>> markNotificationAsRead(
            UUID notificationId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context ->
                grpcNotificationServiceClient
                        .markAsRead(context, notificationId.toString())
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<ResponseEntity<ReadAllNotificationsResponseDto>> markAllNotificationsAsRead(
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context ->
                grpcNotificationServiceClient
                        .markAllAsRead(context)
                        .map(ResponseEntity::ok)
        );
    }
}
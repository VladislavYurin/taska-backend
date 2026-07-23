package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.NotificationsApi;
import ru.taska.domain.dto.NotificationListResponseDto;
import ru.taska.domain.dto.NotificationResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.mapper.NotificationMapper;
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
    private final NotificationMapper notificationMapper;
    private final GatewayRequestExecutor executor;

    /**
     * Возвращает inbox уведомления текущего аутентифицированного пользователя.
     * <p>
     * Эндпоинт является защищённым (PROTECTED). Перед выполнением запроса gateway
     * валидирует Bearer access token, формирует {@code GatewayContext} и передаёт
     * userId из этого контекста в notification-service через gRPC.
     * <p>
     * Frontend может управлять только параметрами фильтрации и пагинации:
     * {@code unreadOnly}, {@code pageSize}, {@code offset}. Передача userId
     * через query parameters, path или body не поддерживается.
     *
     * @param unreadOnly флаг фильтрации только непрочитанных уведомлений
     * @param pageSize   размер страницы уведомлений
     * @param offset     смещение для пагинации
     * @param exchange   текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK)
     * и списком уведомлений внутри {@link NotificationListResponseDto}
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
                        .map(notificationMapper::toRestListResponse)
                        .map(ResponseEntity::ok)
        );
    }

    /**
     * Помечает уведомление текущего аутентифицированного пользователя как прочитанное.
     * <p>
     * Эндпоинт является защищённым (PROTECTED). Gateway берёт userId только из
     * authenticated context и передаёт его вместе с {@code notificationId}
     * в notification-service. Проверка принадлежности уведомления пользователю
     * выполняется на стороне notification-service.
     * <p>
     * Тело запроса отсутствует. Frontend передаёт только идентификатор уведомления
     * в path parameter.
     *
     * @param notificationId идентификатор уведомления, которое нужно отметить прочитанным
     * @param exchange       текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK)
     * и обновлённым уведомлением внутри {@link NotificationResponseDto}
     */
    @Override
    public Mono<ResponseEntity<NotificationResponseDto>> markNotificationAsRead(
            UUID notificationId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, PROTECTED, context ->
                grpcNotificationServiceClient
                        .markAsRead(context, notificationId.toString())
                        .map(response -> {
                            if (!response.hasNotification()) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY,
                                        "Invalid response from notification-service"
                                );
                            }

                            return ResponseEntity.ok(
                                    notificationMapper.toRestResponse(
                                            response.getNotification()
                                    )
                            );
                        })
        );
    }
}
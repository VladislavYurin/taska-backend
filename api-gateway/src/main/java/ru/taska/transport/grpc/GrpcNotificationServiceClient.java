package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsRequestBody;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadRequestBody;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;

import java.util.concurrent.TimeUnit;

/**
 * Реактивный gRPC-клиент для взаимодействия API Gateway с notification-service.
 * <p>
 * Инкапсулирует вызовы удалённых процедур notification-service через
 * {@link ReactorNotificationServiceGrpc.ReactorNotificationServiceStub}.
 * Отвечает за формирование gRPC-заголовков, передачу текущего userId из
 * {@link GatewayContext} и применение deadline из конфигурации приложения.
 * <p>
 * Клиент не принимает userId от frontend-клиента. Идентификатор пользователя
 * всегда берётся из authenticated context, сформированного после проверки Bearer access token.
 */
@Component
@RequiredArgsConstructor
public class GrpcNotificationServiceClient {

    private final ReactorNotificationServiceGrpc.ReactorNotificationServiceStub notificationServiceStub;
    private final GrpcClientProperties properties;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final long DEFAULT_OFFSET = 0L;

    /**
     * Получает inbox уведомления текущего аутентифицированного пользователя.
     * <p>
     * Формирует gRPC запрос {@link ListNotificationsRequest}, добавляя в header
     * requestId и nodeId из {@link GatewayContext}. userId берётся только из
     * {@code context.userContext()}, чтобы frontend не мог получить уведомления другого пользователя.
     * <p>
     * Если параметры пагинации не переданы, применяются значения по умолчанию:
     * {@code pageSize = 20}, {@code offset = 0}. Флаг {@code unreadOnly} по умолчанию считается {@code false}.
     *
     * @param context    текущий gateway context с requestId, nodeId и authenticated userContext
     * @param unreadOnly флаг фильтрации только непрочитанных уведомлений
     * @param pageSize   размер страницы уведомлений
     * @param offset     смещение для пагинации
     * @return {@link Mono} с ответом {@link ListNotificationsResponse} от notification-service
     */
    public Mono<ListNotificationsResponse> listNotifications(
            GatewayContext context,
            Boolean unreadOnly,
            Integer pageSize,
            Long offset
    ) {
        ListNotificationsRequest request = ListNotificationsRequest.newBuilder()
                .setHeader(toHeader(context))
                .setBody(ListNotificationsRequestBody.newBuilder()
                        .setUserId(context.userContext().userId())
                        .setUnreadOnly(Boolean.TRUE.equals(unreadOnly))
                        .setPageSize(pageSize != null ? pageSize : DEFAULT_PAGE_SIZE)
                        .setOffset(offset != null ? offset : DEFAULT_OFFSET)
                        .build())
                .build();

        return stubWithDeadline().listNotifications(request);
    }

    /**
     * Отмечает уведомление текущего аутентифицированного пользователя как прочитанное.
     * <p>
     * Gateway передаёт в notification-service идентификатор уведомления из path parameter
     * и userId из authenticated context. Проверка принадлежности уведомления пользователю
     * остаётся на стороне notification-service.
     *
     * @param context        текущий gateway context с requestId, nodeId и authenticated userContext
     * @param notificationId идентификатор уведомления, которое нужно отметить прочитанным
     * @return {@link Mono} с ответом {@link MarkAsReadResponse}, содержащим обновлённое уведомление
     */
    public Mono<MarkAsReadResponse> markAsRead(
            GatewayContext context,
            String notificationId
    ) {
        MarkAsReadRequest request = MarkAsReadRequest.newBuilder()
                .setHeader(toHeader(context))
                .setBody(MarkAsReadRequestBody.newBuilder()
                        .setNotificationId(notificationId)
                        .setUserId(context.userContext().userId())
                        .build())
                .build();

        return stubWithDeadline().markAsRead(request);
    }

    /**
     * Формирует общий gRPC header для исходящих запросов в notification-service.
     *
     * @param context текущий gateway context
     * @return {@link Header} с requestId и nodeId
     */
    private Header toHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }

    /**
     * Создаёт копию gRPC-стаба с настроенным deadline для notification-service.
     * <p>
     * Значение deadline берётся из {@link GrpcClientProperties.Service}
     * и применяется к каждому исходящему gRPC-вызову.
     *
     * @return {@link ReactorNotificationServiceGrpc.ReactorNotificationServiceStub} с применённым deadline
     */
    private ReactorNotificationServiceGrpc.ReactorNotificationServiceStub stubWithDeadline() {
        return notificationServiceStub.withDeadlineAfter(
                properties.notificationService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }
}

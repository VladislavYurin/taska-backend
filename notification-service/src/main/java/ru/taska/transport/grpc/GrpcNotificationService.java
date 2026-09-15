package ru.taska.transport.grpc;

import java.util.UUID;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.taska.annotation.TrackMetrics;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.mapper.NotificationMapper;
import ru.taska.service.NotificationInboxService;
import validator.GrpcRequestValidators;

import static ru.taska.transport.grpc.logging.GrpcNotificationLogging.logOnError;
import static ru.taska.transport.grpc.logging.GrpcNotificationLogging.logValidationError;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcNotificationService {

    private final NotificationInboxService notificationInboxService;
    private final NotificationMapper notificationMapper;

    @TrackMetrics(counter = "notification-service_list-Notifications_grpc_counter",
                    timer = "notification-service_list-Notifications_grpc_timer")
    public Mono<ListNotificationsResponse> listNotifications(Mono<ListNotificationsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getUserId(), "body.userId")
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(
                                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "listNotifications")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID userId = t.getT3();
                            boolean unreadOnly = req.getBody().getUnreadOnly();
                            int pageSize = req.getBody().getPageSize();
                            long offset = req.getBody().getOffset();

                            log.info("[{}][{}] listNotifications: userId={}, unreadOnly={}, pageSize={}, offset={}",
                                    requestId, nodeId, userId, unreadOnly, pageSize, offset);

                            return notificationInboxService.listNotifications(userId, unreadOnly, pageSize, offset)
                                    .doOnNext(e ->
                                            log.info("[{}][{}] listNotifications: successfully found, userId={}",
                                                    requestId, nodeId, userId)
                                    )
                                    .doOnError(logOnError(requestId, nodeId, "listNotifications"))
                                    .map(notificationMapper::toNotificationProto)
                                    .collectList()
                                    .map(notifications -> ListNotificationsResponse.newBuilder()
                                            .addAllNotifications(notifications)
                                            .build());
                        })
                );
    }

    @TrackMetrics(counter = "notification-service_mark-As-Read_grpc_counter",
            timer = "notification-service_mark-As-Read_grpc_timer")
    public Mono<MarkAsReadResponse> markAsRead(Mono<MarkAsReadRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getNotificationId(), "body.notificationId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getUserId(), "body.userId")
                        )
                        .doOnError(StatusRuntimeException.class,
                                logValidationError(
                                        req.getHeader().getRequestId(), req.getHeader().getNodeId(), "markAsRead")
                        )
                        .flatMap(t -> {
                            String requestId = t.getT1();
                            String nodeId = t.getT2();
                            UUID notificationId = t.getT3();
                            UUID userId = t.getT4();

                            log.info("[{}][{}] markAsRead: notificationId={}, userId={}",
                            requestId, nodeId, notificationId, userId);

                            return notificationInboxService.markAsRead(notificationId, userId)
                                    .doOnSuccess(e ->
                                            log.info("[{}][{}] markAsRead: successfully marked, notificationId={}, userId={}",
                                                    requestId, nodeId, notificationId, userId)
                                    )
                                    .doOnError(logOnError(requestId, nodeId, "markAsRead"));
                        })
                )
                .map(notificationMapper::toNotificationProto)
                .map(this::toMarkAsReadResponse);
    }

    private MarkAsReadResponse toMarkAsReadResponse(NotificationResponse notification) {
        return MarkAsReadResponse.newBuilder()
                .setNotification(notification)
                .build();
    }
}
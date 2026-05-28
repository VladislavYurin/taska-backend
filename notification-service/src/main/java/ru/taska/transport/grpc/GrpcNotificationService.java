package ru.taska.transport.grpc;

import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import io.grpc.StatusRuntimeException;
import io.r2dbc.spi.R2dbcException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcExceptionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.NotificationResponse;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;
import ru.taska.mapper.NotificationMapper;
import ru.taska.service.NotificationInboxService;
import validator.GrpcRequestValidators;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrpcNotificationService extends ReactorNotificationServiceGrpc.NotificationServiceImplBase {

    private final NotificationInboxService notificationInboxService;
    private final NotificationMapper notificationMapper;

    @Override
    public Mono<ListNotificationsResponse> listNotifications(Mono<ListNotificationsRequest> request) {
        return request
                .flatMap(req -> Mono.zip(
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getRequestId(), "header.requestId"),
                        GrpcRequestValidators.requireNonBlankOrInvalidArgument(
                                req.getHeader().getNodeId(), "header.nodeId"),
                        GrpcRequestValidators.parseUuidOrInvalidArgument(
                                req.getBody().getUserId(), "body.userId")
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID userId = t.getT3();
                    boolean unreadOnly = req.getBody().getUnreadOnly();
                    int pageSize = req.getBody().getPageSize();
                    long offset = req.getBody().getOffset();

                    log.info("[{}][{}] listNotifications: userId={}, unreadOnly={}, pageSize={}, offset={}",
                            requestId, nodeId, userId, unreadOnly, pageSize, offset);

                    return notificationInboxService.listNotifications(userId, unreadOnly, pageSize, offset)
                            .map(notificationMapper::toNotificationProto)
                            .collectList()
                            .map(notifications -> ListNotificationsResponse.newBuilder()
                                    .addAllNotifications(notifications)
                                    .build());
                }))
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        e -> {
                            log.error("listNotifications: database error", e);
                            return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
                        })
                .onErrorMap(e -> !(e instanceof DomainException) && !(e instanceof StatusRuntimeException),
                        e -> {
                            log.error("listNotifications: unexpected error", e);
                            return new DomainException(DomainStatus.INTERNAL, "Internal error");
                        })
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    @Override
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
                ).flatMap(t -> {
                    String requestId = t.getT1();
                    String nodeId = t.getT2();
                    UUID notificationId = t.getT3();
                    UUID userId = t.getT4();

                    log.info("[{}][{}] markAsRead: notificationId={}, userId={}",
                            requestId, nodeId, notificationId, userId);

                    return notificationInboxService.markAsRead(notificationId, userId);
                }))
                .map(notificationMapper::toNotificationProto)
                .map(this::toMarkAsReadResponse)
                .onErrorMap(e -> e instanceof R2dbcException || e instanceof TransactionException,
                        e -> {
                            log.error("markAsRead: database error", e);
                            return new DomainException(DomainStatus.UNAVAILABLE, "Database unavailable");
                        })
                .onErrorMap(e -> !(e instanceof DomainException) && !(e instanceof StatusRuntimeException),
                        e -> {
                            log.error("markAsRead: unexpected error", e);
                            return new DomainException(DomainStatus.INTERNAL, "Internal error");
                        })
                .onErrorMap(DomainException.class, GrpcExceptionMapper::toStatusRuntimeException)
                .onErrorMap(GrpcExceptionMapper::toGrpcStatus);
    }

    private MarkAsReadResponse toMarkAsReadResponse(NotificationResponse notification) {
        return MarkAsReadResponse.newBuilder()
                .setNotification(notification)
                .build();
    }
}
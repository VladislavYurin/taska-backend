package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.notification.v1.ListNotificationsRequest;
import ru.taska.api.notification.v1.ListNotificationsResponse;
import ru.taska.api.notification.v1.MarkAsReadRequest;
import ru.taska.api.notification.v1.MarkAsReadResponse;
import ru.taska.api.notification.v1.ReactorNotificationServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class GrpcNotificationServiceAdapter extends ReactorNotificationServiceGrpc.NotificationServiceImplBase {

    private final GrpcNotificationService grpcNotificationService;

    @Override
    public Mono<ListNotificationsResponse> listNotifications(Mono<ListNotificationsRequest> request) {
        return grpcNotificationService.listNotifications(request)
                .transform(GrpcExceptionHandler.withErrorHandling("listNotifications"));
    }

    @Override
    public Mono<MarkAsReadResponse> markAsRead(Mono<MarkAsReadRequest> request) {
        return grpcNotificationService.markAsRead(request)
                .transform(GrpcExceptionHandler.withErrorHandling("markAsRead"));
    }
}

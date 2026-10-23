package ru.taska.transport.grpc;

import exception.GrpcExceptionHandler;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;
import reactor.core.publisher.Mono;
import ru.taska.api.notification.v1.*;
import ru.taska.service.NotificationSettingsService;
import ru.taska.service.TelegramUpdateService;
import validator.GrpcRequestValidators;

@GrpcService
@RequiredArgsConstructor
public class GrpcNotificationSettingsAdapter extends ReactorNotificationSettingsServiceGrpc.NotificationSettingsServiceImplBase {
    private final NotificationSettingsService settings;
    private final TelegramUpdateService updates;

    @Override
    public Mono<NotificationPreferencesResponse> getPreferences(Mono<NotificationSettingsUserRequest> request) {
        return request.flatMap(r -> user(r).flatMap(id -> settings.get(id,r.getEmail())))
                .map(this::map).transform(safeErrors("getNotificationPreferences"));
    }
    @Override
    public Mono<NotificationPreferencesResponse> updatePreferences(Mono<UpdateNotificationPreferencesRequest> request) {
        return request.flatMap(r -> user(r.getUser()).flatMap(id -> settings.update(id,r.getUser().getEmail(),
                        r.hasEmailEnabled() ? r.getEmailEnabled() : null, r.hasTelegramEnabled() ? r.getTelegramEnabled() : null)))
                .map(this::map).transform(safeErrors("updateNotificationPreferences"));
    }
    @Override
    public Mono<TelegramLinkResponse> createTelegramLink(Mono<NotificationSettingsUserRequest> request) {
        return request.flatMap(r -> user(r).flatMap(id -> settings.createLink(id,r.getEmail())))
                .map(link -> TelegramLinkResponse.newBuilder().setLinkId(link.linkId().toString()).setUrl(link.url())
                        .setExpiresAt(link.expiresAt().toString()).build())
                .transform(safeErrors("createTelegramLink"));
    }
    @Override
    public Mono<NotificationPreferencesResponse> confirmTelegramLink(Mono<ConfirmTelegramLinkRequest> request) {
        return request.flatMap(r -> Mono.zip(user(r.getUser()), GrpcRequestValidators.parseUuidOrInvalidArgument(r.getLinkId(), "linkId"))
                        .flatMap(ids -> settings.confirm(ids.getT1(),r.getUser().getEmail(),ids.getT2())))
                .map(this::map).transform(safeErrors("confirmTelegramLink"));
    }
    @Override
    public Mono<NotificationPreferencesResponse> disconnectTelegram(Mono<NotificationSettingsUserRequest> request) {
        return request.flatMap(r -> user(r).flatMap(id -> settings.disconnect(id,r.getEmail())))
                .map(this::map).transform(safeErrors("disconnectTelegram"));
    }
    @Override
    public Mono<TelegramUpdateResponse> processTelegramUpdate(Mono<TelegramUpdateRequest> request) {
        return request.flatMap(r -> updates.process(r.getWebhookSecret(),r.getUpdateJson()))
                .thenReturn(TelegramUpdateResponse.getDefaultInstance())
                .transform(safeErrors("processTelegramUpdate"));
    }
    private Mono<UUID> user(NotificationSettingsUserRequest r) {
        return GrpcRequestValidators.requireNonBlankOrInvalidArgument(r.getHeader().getRequestId(),"header.requestId")
                .then(GrpcRequestValidators.requireNonBlankOrInvalidArgument(r.getHeader().getNodeId(),"header.nodeId"))
                .then(GrpcRequestValidators.parseUuidOrInvalidArgument(r.getUserId(),"userId"));
    }
    private <T> java.util.function.Function<Mono<T>,Mono<T>> safeErrors(String operation) {
        return mono -> mono.onErrorMap(error -> {
            if(error instanceof ru.taska.exception.DomainException || error instanceof io.grpc.StatusRuntimeException) return error;
            boolean database = error instanceof org.springframework.dao.DataAccessException
                    || error instanceof org.springframework.transaction.TransactionException || error instanceof io.r2dbc.spi.R2dbcException;
            // PostgreSQL constraint diagnostics may include private chat IDs/hashes. Strip details before shared logging.
            return new ru.taska.exception.DomainException(database ? ru.taska.exception.DomainStatus.UNAVAILABLE : ru.taska.exception.DomainStatus.INTERNAL,
                    database ? "Notification settings storage is temporarily unavailable" : "Notification settings operation failed");
        }).transform(GrpcExceptionHandler.withErrorHandling(operation));
    }
    private NotificationPreferencesResponse map(NotificationSettingsService.Preferences p) {
        var b = NotificationPreferencesResponse.newBuilder().setEmailEnabled(p.emailEnabled()).setTelegramEnabled(p.telegramEnabled())
                .setEmail(p.email()).setTelegramAvailable(p.telegramAvailable()).setTelegramStatus(p.telegramStatus());
        if(p.telegramUsername()!=null) b.setTelegramUsername(p.telegramUsername());
        if(p.telegramName()!=null) b.setTelegramName(p.telegramName());
        if(p.telegramLinkId()!=null) b.setTelegramLinkId(p.telegramLinkId().toString());
        if(p.telegramLinkExpiresAt()!=null) b.setTelegramLinkExpiresAt(p.telegramLinkExpiresAt().toString());
        if(p.telegramError()!=null) b.setTelegramError(p.telegramError());
        return b.build();
    }
}

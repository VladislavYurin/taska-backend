package ru.taska.transport.grpc;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.notification.v1.*;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;

@Component
@RequiredArgsConstructor
public class GrpcNotificationSettingsClient {
    private final ReactorNotificationSettingsServiceGrpc.ReactorNotificationSettingsServiceStub stub;
    private final GrpcClientProperties properties;

    public Mono<NotificationPreferencesResponse> get(GatewayContext c) { return client().getPreferences(user(c)); }
    public Mono<NotificationPreferencesResponse> update(GatewayContext c, Boolean email, Boolean telegram) {
        var b=UpdateNotificationPreferencesRequest.newBuilder().setUser(user(c));
        if(email!=null) b.setEmailEnabled(email);
        if(telegram!=null) b.setTelegramEnabled(telegram);
        return client().updatePreferences(b.build());
    }
    public Mono<TelegramLinkResponse> link(GatewayContext c) { return client().createTelegramLink(user(c)); }
    public Mono<NotificationPreferencesResponse> confirm(GatewayContext c, String id) {
        return client().confirmTelegramLink(ConfirmTelegramLinkRequest.newBuilder().setUser(user(c)).setLinkId(id).build());
    }
    public Mono<NotificationPreferencesResponse> disconnect(GatewayContext c) { return client().disconnectTelegram(user(c)); }
    public Mono<Void> webhook(String secret,String json) {
        return client().processTelegramUpdate(TelegramUpdateRequest.newBuilder().setWebhookSecret(secret).setUpdateJson(json).build()).then();
    }
    private NotificationSettingsUserRequest user(GatewayContext c) {
        return NotificationSettingsUserRequest.newBuilder().setHeader(Header.newBuilder().setRequestId(c.requestId()).setNodeId(c.nodeId()))
                .setUserId(c.userContext().userId()).setEmail(c.userContext().email()).build();
    }
    private ReactorNotificationSettingsServiceGrpc.ReactorNotificationSettingsServiceStub client() {
        return stub.withDeadlineAfter(properties.notificationService().deadlineDuration().toMillis(),TimeUnit.MILLISECONDS);
    }
}

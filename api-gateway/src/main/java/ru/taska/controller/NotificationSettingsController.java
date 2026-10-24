package ru.taska.controller;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.NotificationSettingsApi;
import ru.taska.api.notification.v1.NotificationPreferencesResponse;
import ru.taska.domain.dto.*;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcNotificationSettingsClient;
import static ru.taska.domain.EndpointSecurity.PROTECTED;

@lombok.extern.slf4j.Slf4j
@RestController
@RequiredArgsConstructor
public class NotificationSettingsController implements NotificationSettingsApi {
    private final GatewayRequestExecutor executor;
    private final GrpcNotificationSettingsClient client;

    @Override
    public Mono<ResponseEntity<NotificationPreferencesDto>> getNotificationPreferences(ServerWebExchange exchange) {
        return executor.execute(exchange,PROTECTED,c -> client.get(c).map(p -> map(p,c.requestId())).map(ResponseEntity::ok));
    }
    @Override
    public Mono<ResponseEntity<NotificationPreferencesDto>> updateNotificationPreferences(
            Mono<NotificationPreferencesPatchDto> request, ServerWebExchange exchange) {
        return executor.execute(exchange,PROTECTED,c -> request.flatMap(p -> client.update(c,p.getEmailEnabled(),p.getTelegramEnabled()))
                .map(p -> map(p,c.requestId())).map(ResponseEntity::ok));
    }
    @Override
    public Mono<ResponseEntity<TelegramLinkDto>> createTelegramLink(ServerWebExchange exchange) {
        return executor.execute(exchange,PROTECTED,c -> client.link(c).flatMap(link -> {
            // Older workers may omit the snapshot during a rolling restart.
            var preferences = link.hasPreferences() ? Mono.just(link.getPreferences()) : client.get(c)
                    .filter(p -> link.getLinkId().equals(p.getTelegramLinkId())
                            && !p.getTelegramLinkExpiresAt().isEmpty()
                            && OffsetDateTime.parse(link.getExpiresAt()).toInstant()
                                .equals(OffsetDateTime.parse(p.getTelegramLinkExpiresAt()).toInstant()))
                    .switchIfEmpty(Mono.error(io.grpc.Status.ABORTED
                            .withDescription("Telegram link was replaced; create a new link").asRuntimeException()));
            return preferences.map(p -> {
                var dto=new TelegramLinkDto();
                dto.setLinkId(UUID.fromString(link.getLinkId()));
                dto.setUrl(java.net.URI.create(link.getUrl()));
                dto.setExpiresAt(OffsetDateTime.parse(link.getExpiresAt()));
                dto.setPreferences(map(p,c.requestId()));
                return ResponseEntity.ok(dto);
            });
        }));
    }

    @Override
    public Mono<ResponseEntity<NotificationPreferencesDto>> confirmTelegramLink(
            Mono<ConfirmTelegramLinkDto> request, ServerWebExchange exchange) {
        return executor.execute(exchange,PROTECTED,c -> request.flatMap(p -> client.confirm(c,p.getLinkId().toString()))
                .map(p -> map(p,c.requestId())).map(ResponseEntity::ok));
    }
    @Override
    public Mono<ResponseEntity<NotificationPreferencesDto>> disconnectTelegram(ServerWebExchange exchange) {
        return executor.execute(exchange,PROTECTED,c -> client.disconnect(c).map(p -> map(p,c.requestId())).map(ResponseEntity::ok));
    }

    private NotificationPreferencesDto map(NotificationPreferencesResponse p, String requestId) {
        var dto=new NotificationPreferencesDto();
        dto.setEmailEnabled(p.getEmailEnabled());
        dto.setTelegramEnabled(p.getTelegramEnabled());
        dto.setEmail(p.getEmail());
        dto.setTelegramAvailable(p.getTelegramAvailable());
        var status = java.util.Arrays.stream(NotificationPreferencesDto.TelegramStatusEnum.values())
                .filter(value -> value.getValue().equals(p.getTelegramStatus())).findFirst()
                .orElse(NotificationPreferencesDto.TelegramStatusEnum.UNKNOWN);
        if (status == NotificationPreferencesDto.TelegramStatusEnum.UNKNOWN) {
            log.warn("[{}] Unknown notification Telegram status: {}", requestId, p.getTelegramStatus());
        }
        dto.setTelegramStatus(status);
        dto.setTelegramUsername(p.getTelegramUsername().isEmpty()?null:p.getTelegramUsername());
        dto.setTelegramName(p.getTelegramName().isEmpty()?null:p.getTelegramName());
        dto.setTelegramLinkId(p.getTelegramLinkId().isEmpty()?null:UUID.fromString(p.getTelegramLinkId()));
        dto.setTelegramLinkExpiresAt(p.getTelegramLinkExpiresAt().isEmpty()?null:OffsetDateTime.parse(p.getTelegramLinkExpiresAt()));
        dto.setTelegramError(p.getTelegramError().isEmpty()?null:p.getTelegramError());
        return dto;
    }
}

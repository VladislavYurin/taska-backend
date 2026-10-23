package ru.taska.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcNotificationSettingsClient;
import static ru.taska.domain.EndpointSecurity.PUBLIC;

/** No JWT on this one route. The shared webhook credential is checked by notification-service. */
@RestController
@RequiredArgsConstructor
public class TelegramWebhookController {
    private final GatewayRequestExecutor executor;
    private final GrpcNotificationSettingsClient client;
    @Value("${NOTIFICATION_TELEGRAM_ENABLED:false}") private boolean enabled;
    @Value("${NOTIFICATION_TELEGRAM_WEBHOOK_SECRET:}") private String webhookSecret;
    private long window;
    private int requests;

    @PostMapping(value="/api/v1/integrations/telegram/webhook",consumes="application/json")
    public Mono<Void> webhook(@RequestHeader(value="X-Telegram-Bot-Api-Secret-Token",defaultValue="") String secret,
            ServerWebExchange exchange) {
        return executor.execute(exchange,PUBLIC,c -> Mono.defer(() -> {
            if(!enabled) return Mono.error(Status.UNAVAILABLE.withDescription("Telegram notifications are not configured").asRuntimeException());
            if(webhookSecret.isEmpty() || !MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8),secret.getBytes(StandardCharsets.UTF_8)))
                return Mono.error(Status.PERMISSION_DENIED.withDescription("Invalid Telegram webhook credentials").asRuntimeException());
            if(!allow()) return Mono.error(Status.RESOURCE_EXHAUSTED.withDescription("Telegram webhook rate exceeded").asRuntimeException());
            return DataBufferUtils.join(exchange.getRequest().getBody(),32768)
                .map(buffer -> {
                    try { return buffer.toString(StandardCharsets.UTF_8); }
                    finally { DataBufferUtils.release(buffer); }
                }).defaultIfEmpty("").flatMap(json -> client.webhook(secret,json))
                    .onErrorMap(DataBufferLimitException.class, e -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Telegram update is too large"));
        }));
    }
    private synchronized boolean allow() {
        long now=System.nanoTime()/1_000_000_000L;
        if(window!=now) { window=now;requests=0; }
        return ++requests<=60;
    }
}

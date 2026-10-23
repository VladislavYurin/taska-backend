package ru.taska.service;

import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import ru.taska.config.props.TelegramProperties;
import ru.taska.domain.Notification;
import ru.taska.domain.NotificationType;
import ru.taska.mapper.NotificationMapper;
import tools.jackson.databind.JsonNode;

@Service
public class TelegramSender {
    private final TelegramProperties properties;
    private final WebClient client;
    private final String appUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramSender(TelegramProperties properties, @Value("${notification.app-url:http://localhost:5173}") String appUrl) {
        this(properties, appUrl, WebClient.builder()
                // No observations/wiretap: Bot API puts its credential in the URI path.
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().wiretap(false)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(65536))
                .baseUrl("https://api.telegram.org").build());
    }

    TelegramSender(TelegramProperties properties, String appUrl, WebClient client) {
        this.properties = properties;
        this.client = client;
        this.appUrl = appUrl.replaceAll("/+$", "");
        URI uri = URI.create(appUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                || uri.getFragment() != null && !uri.getFragment().isEmpty() && !"/".equals(uri.getFragment())) {
            throw new IllegalArgumentException("Telegram app URL must be an HTTPS root or hash root (local HTTP allowed)");
        }
    }

    public Mono<Void> notification(long chat, Notification notification) {
        String title = notification.getTitle() == null ? "Taska" : notification.getTitle();
        String body = NotificationMapper.safeBody(notification);
        String text = TelegramUpdateService.clipped(title, 256)
                + (body == null || body.isBlank() ? "" : "\n\n" + TelegramUpdateService.clipped(body, 1600));
        String key = notification.getIssueKey();
        String url = key == null || key.isBlank() || key.length() > 128 || notification.getIssueId() == null
                || notification.getProjectId() == null || notification.getNotificationType() == NotificationType.ISSUE_DELETED
                ? null : appUrl + "/browse/" + UriUtils.encodePathSegment(key, StandardCharsets.UTF_8);
        return send(chat, text, url);
    }

    public Mono<Void> reply(long chat, String text) { return send(chat, text, null); }

    private Mono<Void> send(long chat, String text, String url) {
        if (!properties.isEnabled()) return Mono.error(new DeliveryFailure("TELEGRAM_DISABLED", false, false, 60));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chat);
        payload.put("text", text);
        // Plain text avoids all HTML/Markdown injection and escaping ambiguities.
        payload.put("link_preview_options", Map.of("is_disabled", true));
        if (url != null) payload.put("reply_markup", Map.of("inline_keyboard",
                List.of(List.of(Map.of("text", "Open in Taska", "url", url)))));
        return client.post().uri("/bot" + properties.getBotToken() + "/sendMessage").bodyValue(payload)
                .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                        .switchIfEmpty(Mono.error(new DeliveryFailure("TELEGRAM_INVALID_RESPONSE", false, false, 30)))
                        .flatMap(json -> {
                            if (response.statusCode().is2xxSuccessful() && json.path("ok").asBoolean(false)) return Mono.<Void>empty();
                            int code = json.path("error_code").asInt(response.statusCode().value());
                            if (code == 429) return Mono.error(new DeliveryFailure("TELEGRAM_RATE_LIMIT", false, false,
                                    Math.max(1, Math.min(86400, json.path("parameters").path("retry_after").asLong(60)))));
                            boolean permanent = code == 400 || code == 403;
                            return Mono.error(new DeliveryFailure(permanent ? "TELEGRAM_DELIVERY_UNAVAILABLE" : "TELEGRAM_TEMPORARY",
                                    permanent, permanent, 60));
                        }))
                .timeout(properties.getTimeout())
                .onErrorMap(error -> error instanceof DeliveryFailure ? error
                        : new DeliveryFailure("TELEGRAM_TEMPORARY", false, false, 30));
    }
}

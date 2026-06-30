package ru.taska.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Извлекает Bearer-токен из заголовка {@code Authorization} входящего запроса.
 */
@Slf4j
@Component
public class BearerTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Извлекает Bearer-токен из заголовка {@code Authorization}.
     *
     * @param exchange  текущий запрос
     * @param requestId идентификатор запроса для логирования
     * @return значение токена без префикса {@code Bearer }
     * @throws ResponseStatusException {@code 401}, если заголовок отсутствует,
     *         не начинается с {@code Bearer } или токен пустой
     */
    public String extract(ServerWebExchange exchange, String requestId) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null) {
            log.warn("[{}] Authorization header is missing", requestId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization header is missing");
        }

        if (!authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("[{}] Invalid Authorization header format", requestId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization header format");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (token.isBlank()) {
            log.warn("[{}] Bearer token is blank", requestId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bearer token is blank");
        }

        log.info("[{}] Token successfully extracted from Authorization header", requestId);
        return token;
    }
}

package ru.taska.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * Разрешает идентификатор запроса для целей трассировки.
 */
@Slf4j
@Component
public class RequestIdProvider {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String REQUEST_ID_ATTR = "ru.taska.requestId";

    /**
     * Возвращает идентификатор запроса из атрибутов exchange (если уже был разрешён ранее),
     * затем из заголовка {@code X-Request-Id}, иначе генерирует новый UUID.
     * Результат сохраняется в атрибутах exchange для идемпотентности повторных вызовов.
     *
     * @param exchange текущий запрос
     * @return идентификатор запроса
     */
    public String resolve(ServerWebExchange exchange) {
        String cached = (String) exchange.getAttributes().get(REQUEST_ID_ATTR);
        if (cached != null) {
            return cached;
        }

        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);

        if (requestId != null && !requestId.isBlank()) {
            log.debug("[{}] Using X-Request-Id from incoming request", requestId);
        } else {
            requestId = UUID.randomUUID().toString();
            log.debug("[{}] X-Request-Id not provided by client, generated new one", requestId);
        }

        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        return requestId;
    }
}

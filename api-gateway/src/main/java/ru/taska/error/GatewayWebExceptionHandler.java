package ru.taska.error;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import ru.taska.filter.RequestIdProvider;

/**
 * Перехватывает все необработанные исключения из цепочки обработки запроса
 * и формирует JSON-ответ через {@link GatewayErrorHandler}.
 * Порядок {@code -2} гарантирует выполнение раньше стандартного
 * {@code DefaultErrorWebExceptionHandler} Spring Boot (порядок {@code -1}).
 */
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayWebExceptionHandler implements WebExceptionHandler {

    private final GatewayErrorHandler errorHandler;
    private final RequestIdProvider requestIdProvider;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        String requestId = requestIdProvider.resolve(exchange);
        return errorHandler.handleError(exchange, ex, requestId);
    }
}

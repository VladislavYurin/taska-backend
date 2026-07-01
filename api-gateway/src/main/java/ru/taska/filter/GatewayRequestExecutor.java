package ru.taska.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;

import java.util.function.Function;

/**
 * Центральный компонент обработки запросов в API Gateway.
 * Разрешает идентификатор запроса, делегирует построение {@link ru.taska.domain.GatewayContext}
 * фабрике {@link GatewayContextFactory} и передаёт готовый контекст в обработчик.
 * Ошибки передаются через {@code Mono.error} и перехватываются
 * на уровне {@link ru.taska.error.GatewayWebExceptionHandler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRequestExecutor {

    private final RequestIdProvider requestIdProvider;
    private final GatewayContextFactory contextFactory;

    /**
     * Формирует {@link ru.taska.domain.GatewayContext} и передаёт его в обработчик {@code action}.
     *
     * @param exchange текущий запрос
     * @param secured  тип безопасности эндпоинта; определяет способ построения контекста
     * @param action   обработчик, принимающий собранный {@link ru.taska.domain.GatewayContext}
     * @param <RES>    тип результата обработчика
     * @return результат выполнения {@code action}
     */
    public <RES> Mono<RES> execute(
            ServerWebExchange exchange,
            EndpointSecurity secured,
            Function<GatewayContext, Mono<RES>> action) {

        return Mono.defer(() -> {
            String requestId = requestIdProvider.resolve(exchange);

            log.info("[{}] Executing request, secured={}", requestId, secured);

            return contextFactory.buildContext(requestId, exchange, secured).flatMap(action);
        });
    }
}

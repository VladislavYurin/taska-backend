package ru.taska.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

import java.util.function.Function;

/**
 * Центральный компонент обработки запросов в API Gateway.
 * Разрешает идентификатор запроса, при необходимости аутентифицирует пользователя
 * через auth-сервис, собирает {@link ru.taska.domain.GatewayContext} и передаёт его
 * в обработчик. Ошибки передаются через {@code Mono.error} и перехватываются
 * на уровне {@link ru.taska.error.GatewayWebExceptionHandler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRequestExecutor {

    private static final String NODE_ID = "api-gateway";

    private final RequestIdProvider requestIdProvider;
    private final BearerTokenExtractor bearerTokenExtractor;
    private final GrpcAuthServiceClient authServiceClient;
    private final ContextMapper contextMapper;

    /**
     * Формирует {@link ru.taska.domain.GatewayContext} и передаёт его в обработчик {@code action}.
     *
     * @param exchange текущий запрос
     * @param secured  {@code true} — требует валидного Bearer-токена;
     *                 {@code false} — контекст создаётся без аутентификации
     * @param action   обработчик, принимающий собранный {@link ru.taska.domain.GatewayContext}
     * @param <RES>    тип результата обработчика
     * @return результат выполнения {@code action}
     */
    public <RES> Mono<RES> execute(
            ServerWebExchange exchange,
            boolean secured,
            Function<GatewayContext, Mono<RES>> action) {

        return Mono.defer(() -> {
            String requestId = requestIdProvider.resolve(exchange);

            log.info("[{}] Executing request, secured={}", requestId, secured);

            Mono<GatewayContext> contextMono = secured ?
                    buildSecuredContext(exchange, requestId) :
                    Mono.just(new GatewayContext(requestId, NODE_ID, null));

            return contextMono.flatMap(action);
        });
    }

    private Mono<GatewayContext> buildSecuredContext(ServerWebExchange exchange, String requestId) {
        return Mono.fromCallable(() -> bearerTokenExtractor.extract(exchange, requestId))
                .flatMap(token -> authServiceClient.validateAccessToken(requestId, NODE_ID, token))
                .map(response -> {
                    var proto = response.getUserContext();
                    GatewayUserContext userContext = contextMapper.mapToGatewayUserContext(proto);
                    log.info("[{}] User context resolved: userId={}", requestId, proto.getUserId());
                    return new GatewayContext(requestId, NODE_ID, userContext);
                });
    }
}

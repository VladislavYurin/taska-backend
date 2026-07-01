package ru.taska.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

/**
 * Фабрика для построения {@link ru.taska.domain.GatewayContext} в зависимости от типа
 * безопасности эндпоинта.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayContextFactory {

    @Value("${spring.application.name}")
    private String nodeId;

    private final BearerTokenExtractor bearerTokenExtractor;
    private final GrpcAuthServiceClient authServiceClient;
    private final ContextMapper contextMapper;

    /**
     * Строит {@link ru.taska.domain.GatewayContext} в соответствии с типом безопасности эндпоинта.
     *
     * @param requestId идентификатор запроса
     * @param exchange  текущий запрос
     * @param secured   тип безопасности эндпоинта
     * @return контекст запроса
     */
    public Mono<GatewayContext> buildContext(
            String requestId,
            ServerWebExchange exchange,
            EndpointSecurity secured
    ) {
        return switch (secured) {
            case PUBLIC -> Mono.just(new GatewayContext(requestId, nodeId, null));
            case PROTECTED -> buildSecuredContext(exchange, requestId);
        };
    }

    /**
     * Строит контекст для защищённого эндпоинта: извлекает Bearer-токен из запроса,
     * валидирует его через auth-сервис и маппит пользовательский контекст из ответа.
     *
     * @param exchange  текущий запрос
     * @param requestId идентификатор запроса
     * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayUserContext}
     */
    private Mono<GatewayContext> buildSecuredContext(ServerWebExchange exchange, String requestId) {
        return Mono.fromCallable(() -> bearerTokenExtractor.extract(exchange, requestId))
                .flatMap(token -> authServiceClient.validateAccessToken(requestId, nodeId, token))
                .map(response -> {
                    var proto = response.getUserContext();
                    GatewayUserContext userContext = contextMapper.mapToGatewayUserContext(proto);
                    log.info("[{}] User context resolved: userId={}", requestId, proto.getUserId());
                    return new GatewayContext(requestId, nodeId, userContext);
                });
    }
}

package ru.taska.domain;

/**
 * Контекст запроса. Содержит идентификаторы для распределённой трассировки
 * и данные аутентифицированного пользователя.
 *
 * @param requestId уникальный идентификатор входящего запроса. Берётся из заголовка
 *                  {@code X-Request-Id}, если он присутствует; иначе генерируется
 *                  автоматически.
 * @param nodeId    идентификатор узла-отправителя (константа {@code "api-gateway"}).
 *                  Пробрасывается в gRPC-запросы, чтобы downstream-сервисы знали источник вызова.
 * @param userContext данные аутентифицированного пользователя, полученные от auth-сервиса
 *                    при валидации Bearer-токена. {@code null} для незащищённых эндпоинтов
 *                    ({@code secured = false}).
 */
public record GatewayContext(
        String requestId,
        String nodeId,
        GatewayUserContext userContext,
        ru.taska.domain.ActionChannel channel
) {
    public GatewayContext(String requestId, String nodeId, GatewayUserContext userContext) {
        this(requestId, nodeId, userContext, userContext == null ? null
                : userContext.tokenKind() == GatewayTokenKind.DELEGATION ? ru.taska.domain.ActionChannel.MCP
                : userContext.tokenKind() == GatewayTokenKind.PAT ? ru.taska.domain.ActionChannel.API_TOKEN
                : ru.taska.domain.ActionChannel.UI);
    }
}

package ru.taska.filter;

import io.grpc.Status;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mapper.GrpcErrorDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayTokenKind;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.GlobalRole;
import ru.taska.mapper.ContextMapper;
import ru.taska.transport.grpc.GrpcAuthServiceClient;

/**
 * Фабрика для построения {@link ru.taska.domain.GatewayContext} в зависимости от типа безопасности
 * эндпоинта.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayContextFactory {

  @Value("${spring.application.name}")
  private String nodeId;

  private static final EndpointAccessPolicy PAT_POLICY = EndpointAccessPolicy.load();

  private final BearerTokenExtractor bearerTokenExtractor;
  private final GrpcAuthServiceClient authServiceClient;
  private final ContextMapper contextMapper;

  /**
   * Строит {@link ru.taska.domain.GatewayContext} в соответствии с типом безопасности эндпоинта.
   *
   * @param requestId идентификатор запроса
   * @param exchange текущий запрос
   * @param secured тип безопасности эндпоинта
   * @return контекст запроса
   */
  public Mono<GatewayContext> buildContext(
      String requestId, ServerWebExchange exchange, EndpointSecurity secured) {
    return switch (secured) {
      case PUBLIC -> Mono.just(new GatewayContext(requestId, nodeId, null));
      case PROTECTED, JWT_SESSION_REQUIRED -> buildSecuredContext(exchange, requestId, secured);
      case GLOBAL_ADMIN_REQUIRED -> buildAdminContext(exchange, requestId);
    };
  }

  /**
   * Строит контекст для защищённого эндпоинта: извлекает Bearer-токен из запроса и валидирует его
   * через auth-сервис.
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayUserContext}
   */
  private Mono<GatewayUserContext> resolveUserContext(
      ServerWebExchange exchange, String requestId) {
    return Mono.fromCallable(() -> bearerTokenExtractor.extract(exchange, requestId))
        .flatMap(token -> authServiceClient.validateAccessToken(requestId, nodeId, token))
        .map(
            response -> {
              var proto = response.getUserContext();
              log.info(
                  "[{}] User context resolved: userId={}, tokenKind={}, tokenId={}",
                  requestId,
                  proto.getUserId(),
                  proto.getTokenKind(),
                  proto.hasTokenId() ? proto.getTokenId() : null);
              return contextMapper.mapToGatewayUserContext(proto);
            });
  }

  /**
   * Маппит пользовательский контекст полученный в resloveUserContext
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayContext}
   */
  private Mono<GatewayContext> buildSecuredContext(
      ServerWebExchange exchange, String requestId, EndpointSecurity security) {
    return resolveUserContext(exchange, requestId)
        .map(
            userContext -> {
              enforcePatPolicy(exchange, userContext, security);
              return new GatewayContext(requestId, nodeId, userContext);
            });
  }

  /**
   * Проверяет глобальную роль пользователя и маппит пользовательский контекст полученный в
   * resloveUserContext
   *
   * @param exchange текущий запрос
   * @param requestId идентификатор запроса
   * @return контекст запроса с заполненным {@link ru.taska.domain.GatewayContext}
   */
  private Mono<GatewayContext> buildAdminContext(ServerWebExchange exchange, String requestId) {
    return resolveUserContext(exchange, requestId)
        .flatMap(
            userContext -> {
              enforcePatPolicy(exchange, userContext, EndpointSecurity.GLOBAL_ADMIN_REQUIRED);
              if (!userContext.globalRole().equals(GlobalRole.GLOBAL_ADMIN)) {
                log.debug("User doesn't have permission: {}", userContext.userId());
                return Mono.error(
                    Status.PERMISSION_DENIED
                        .withDescription("User doesn't have permission")
                        .asRuntimeException());
              }
              return Mono.just(new GatewayContext(requestId, nodeId, userContext));
            });
  }

  private static void enforcePatPolicy(
      ServerWebExchange exchange, GatewayUserContext user, EndpointSecurity security) {
    if (security == EndpointSecurity.JWT_SESSION_REQUIRED) {
      exchange.getResponse().getHeaders().setCacheControl("private, no-store");
    }
    if (user.tokenKind() != GatewayTokenKind.PAT) return;
    String path = EndpointAccessPolicy.canonicalPath(exchange.getRequest());
    String scope = PAT_POLICY.scope(exchange.getRequest());
    if (security == EndpointSecurity.GLOBAL_ADMIN_REQUIRED
        || security == EndpointSecurity.JWT_SESSION_REQUIRED
        || "session".equals(scope)
        || path.startsWith("/api/v1/admin/")
        || path.startsWith("/api/v1/readonly/")) {
      throw Status.PERMISSION_DENIED
          .withDescription("A JWT session is required")
          .asRuntimeException(GrpcErrorDetails.encode(Map.of("reason", "SESSION_REQUIRED")));
    }
    if (!user.scopes().contains(scope)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Insufficient token scope")
          .asRuntimeException(
              GrpcErrorDetails.encode(
                  Map.of("reason", "INSUFFICIENT_SCOPE", "requiredScope", scope)));
    }
  }
}

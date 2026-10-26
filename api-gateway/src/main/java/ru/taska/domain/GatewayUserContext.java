package ru.taska.domain;

import java.util.List;
import lombok.Builder;

/**
 * Контекст аутентифицированного пользователя. Заполняется на основе ответа auth-сервиса после
 * успешной валидации Bearer-токена и доступен в {@link ru.taska.domain.GatewayContext} для
 * защищённых эндпоинтов.
 *
 * @param userId уникальный идентификатор пользователя
 * @param email адрес электронной почты пользователя
 * @param displayName отображаемое имя пользователя
 * @param status статус учётной записи пользователя
 * @param globalRole глобальная роль пользователя
 */
@Builder
public record GatewayUserContext(
    String userId,
    String email,
    String displayName,
    GatewayUserStatus status,
    GlobalRole globalRole,
    GatewayTokenKind tokenKind,
    String tokenId,
    List<String> scopes) {
  public GatewayUserContext {
    tokenKind = tokenKind == null ? GatewayTokenKind.JWT : tokenKind;
    scopes = scopes == null ? List.of() : List.copyOf(scopes);
  }

  /** Compatibility for existing JWT callers and fixtures. */
  public GatewayUserContext(
      String userId,
      String email,
      String displayName,
      GatewayUserStatus status,
      GlobalRole globalRole) {
    this(userId, email, displayName, status, globalRole, GatewayTokenKind.JWT, null, List.of());
  }
}

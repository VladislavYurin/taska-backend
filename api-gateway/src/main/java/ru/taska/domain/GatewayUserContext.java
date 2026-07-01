package ru.taska.domain;

import ru.taska.api.common.v1.UserStatus;

/**
 * Контекст аутентифицированного пользователя.
 * Заполняется на основе ответа auth-сервиса после успешной валидации Bearer-токена
 * и доступен в {@link ru.taska.domain.GatewayContext} для защищённых эндпоинтов.
 *
 * @param userId      уникальный идентификатор пользователя
 * @param login       логин пользователя
 * @param email       адрес электронной почты пользователя
 * @param displayName отображаемое имя пользователя
 * @param status      статус учётной записи пользователя
 */
public record GatewayUserContext(
        String userId,
        String login,
        String email,
        String displayName,
        UserStatus status
) {

}

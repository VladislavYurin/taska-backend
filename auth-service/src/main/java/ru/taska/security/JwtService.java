package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.entity.User;

public interface JwtService {
    /**
     * Генерирует JWT access-токен для пользователя.
     *
     * @param user пользователь, для которого создаётся токен
     * @return токен в виде строки
     */
    Mono<String> generateAccessToken(User user);

    /**
     * Возвращает время жизни access-токена в секундах.
     *
     * @return TTL в секундах
     */
    Mono<Long> getExpiresIn();

    String hashToken(String rawToken);
}

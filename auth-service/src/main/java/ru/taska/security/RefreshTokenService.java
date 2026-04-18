package ru.taska.security;

import reactor.core.publisher.Mono;
import ru.taska.dto.RefreshTokenResponseDto;
import ru.taska.entity.User;

/**
 * Сервис управления refresh-токенами (создание, валидация, ротация).
 */
public interface RefreshTokenService {
    /**
     * Создаёт новый refresh-токен для пользователя.
     *
     * @param user пользователь
     * @return сырой (не хэшированный) refresh-токен
     */
    Mono<String> createRefreshToken(User user);

    /**
     * Проверяет валидность refresh-токена, при успехе выполняет ротацию:
     * старый токен отзывается, создаётся новый.
     *
     * @param rawToken сырой refresh-токен
     * @return DTO с новой сущностью и сырым токеном
     */
    Mono<RefreshTokenResponseDto> validateAndRotate(String rawToken);
}

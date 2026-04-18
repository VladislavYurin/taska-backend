package ru.taska.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import ru.taska.entity.RefreshToken;

/**
 * DTO результата валидации и ротации refresh-токена.
 *
 * <p>Возвращается при успешной проверке refresh-токена и создании новой пары
 * токенов. Содержит как сохранённую в БД сущность (с хэшем), так и сырой токен
 * для передачи клиенту.</p>
 *
 * <p>Разделение на {@code refreshToken} и {@code rawToken} обусловлено
 * соображениями безопасности:</p>
 * <ul>
 *     <li>{@code refreshToken} — хранится в БД, содержит хэш и метаданные,
 *         не может быть использован для аутентификации напрямую</li>
 *     <li>{@code rawToken} — передаётся клиенту один раз, используется для
 *         получения новой пары токенов</li>
 * </ul>
 *
 * <p>Поля {@code refreshToken.replacedBy} и {@code refreshToken.revokedAt}
 * позволяют отслеживать цепочку ротации и отозванные токены.</p>
 *
 * @see RefreshToken
 * @see ru.taska.security.RefreshTokenService#validateAndRotate(String)
 */
@Getter
@AllArgsConstructor
public class RefreshTokenResponseDto {
    private final RefreshToken refreshToken;
    private final String rawToken;
}
